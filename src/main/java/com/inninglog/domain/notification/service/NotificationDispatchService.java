package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.config.NotificationDispatchProperties;
import com.inninglog.domain.notification.entity.NotificationDeliveryTarget;
import com.inninglog.domain.notification.entity.NotificationOutbox;
import com.inninglog.domain.notification.entity.NotificationOutboxStatus;
import com.inninglog.domain.notification.entity.NotificationTargetStatus;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.entity.UserPushToken;
import com.inninglog.domain.notification.repository.NotificationDeliveryTargetRepository;
import com.inninglog.domain.notification.repository.NotificationOutboxRepository;
import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
    private static final Collection<NotificationTargetStatus> CLAIMABLE_STATUSES = List.of(
            NotificationTargetStatus.PENDING,
            NotificationTargetStatus.PROCESSING,
            NotificationTargetStatus.RETRY);
    private static final Collection<NotificationTargetStatus> FAILED_STATUSES = List.of(
            NotificationTargetStatus.INVALID,
            NotificationTargetStatus.DEAD);

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationDeliveryTargetRepository targetRepository;
    private final UserPushTokenRepository pushTokenRepository;
    private final NotificationDeliveryService deliveryService;
    private final NotificationPayloadCodec payloadCodec;
    private final NotificationDispatchProperties properties;
    private final NotificationMetrics metrics;
    private final Clock clock;
    private final TransactionTemplate requiresNewTransaction;

    public NotificationDispatchService(
            NotificationOutboxRepository outboxRepository,
            NotificationDeliveryTargetRepository targetRepository,
            UserPushTokenRepository pushTokenRepository,
            NotificationDeliveryService deliveryService,
            NotificationPayloadCodec payloadCodec,
            NotificationDispatchProperties properties,
            NotificationMetrics metrics,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.outboxRepository = outboxRepository;
        this.targetRepository = targetRepository;
        this.pushTokenRepository = pushTokenRepository;
        this.deliveryService = deliveryService;
        this.payloadCodec = payloadCodec;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.requiresNewTransaction = new TransactionTemplate(transactionManager);
        this.requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Claims work in a short transaction, calls FCM without a database transaction, and
     * records the result in a second short transaction. A processing lease makes a claim
     * recoverable after a worker crash; delivery is therefore intentionally at-least-once.
     */
    public boolean dispatchNext() {
        ClaimAttempt attempt = requiresNewTransaction.execute(
                ignored -> claimNextInTransaction(clock.instant()));
        if (attempt == null || !attempt.didWork()) {
            return false;
        }
        if (attempt.claim() == null) {
            return true;
        }

        DispatchClaim claim = attempt.claim();
        ClaimCompletion completion;
        long startedNanos = System.nanoTime();
        try {
            PushBatchResult result = deliveryService.deliver(claim.notification(), claim.targets());
            completion = ClaimCompletion.from(result);
            if (result.failureCount() == 0) {
                log.info(
                        "Push batch processed: outboxId={}, type={}, targets={}, success={}",
                        claim.outboxId(),
                        claim.notificationType(),
                        result.targetResults().size(),
                        result.successCount());
            } else {
                log.warn(
                        "Push batch completed with failures: outboxId={}, type={}, targets={}, success={}, failures={}",
                        claim.outboxId(),
                        claim.notificationType(),
                        result.targetResults().size(),
                        result.successCount(),
                        summarizeFailures(result));
            }
        } catch (InvalidPushPayloadException exception) {
            completion = ClaimCompletion.allTerminal("INVALID_PAYLOAD");
            log.error("Push payload rejected: outboxId={}, type={}",
                    claim.outboxId(), claim.notificationType());
        } catch (RuntimeException exception) {
            completion = ClaimCompletion.allRetryable("DISPATCH_EXCEPTION");
            log.warn("Push dispatch failed unexpectedly: outboxId={}, type={}, exceptionType={}",
                    claim.outboxId(), claim.notificationType(), exception.getClass().getSimpleName());
        } finally {
            metrics.recordDispatchDuration(
                    claim.notificationType(),
                    Duration.ofNanos(System.nanoTime() - startedNanos));
        }

        ClaimCompletion finalCompletion = completion;
        requiresNewTransaction.executeWithoutResult(
                ignored -> completeClaimInTransaction(claim, finalCompletion, clock.instant()));
        return true;
    }

    private ClaimAttempt claimNextInTransaction(Instant now) {
        List<NotificationDeliveryTarget> ready = targetRepository.findNextReadyForUpdate(
                CLAIMABLE_STATUSES,
                now,
                PageRequest.of(0, 1));
        if (ready.isEmpty()) {
            return expandNextOutbox(now);
        }

        NotificationOutbox outbox = ready.getFirst().getOutbox();
        List<NotificationDeliveryTarget> batch = targetRepository.findReadyBatchForOutboxForUpdate(
                outbox.getId(),
                CLAIMABLE_STATUSES,
                now,
                PageRequest.of(0, properties.batchSize()));
        if (batch.isEmpty()) {
            return ClaimAttempt.workedWithoutClaim();
        }

        Map<Long, UserPushToken> registrationsById = pushTokenRepository.findAllById(batch.stream()
                        .map(NotificationDeliveryTarget::getPushRegistrationId)
                        .toList()).stream()
                .collect(Collectors.toMap(UserPushToken::getId, Function.identity()));

        List<NotificationDeliveryTarget> claimableTargets = new ArrayList<>();
        List<PushTarget> pushTargets = new ArrayList<>();
        for (NotificationDeliveryTarget target : batch) {
            UserPushToken registration = registrationsById.get(target.getPushRegistrationId());
            if (registration == null
                    || !registration.isEnabled()
                    || !registration.getUserId().equals(outbox.getUserId())) {
                target.cancelOwnership(now);
                metrics.recordCancelledOwnership(outbox.getNotificationType());
                continue;
            }
            if (target.getAttemptCount() >= properties.maxAttempts()) {
                target.markDead("CLAIM_LEASE_EXHAUSTED", now);
                metrics.recordTargetFailure(
                        outbox.getNotificationType(),
                        PushTargetOutcome.TERMINAL_FAILURE,
                        "CLAIM_LEASE_EXHAUSTED");
                continue;
            }
            claimableTargets.add(target);
            pushTargets.add(new PushTarget(
                    target.getId(),
                    registration.getId(),
                    registration.getPushToken()));
        }

        if (pushTargets.isEmpty()) {
            completeOutboxWhenFinished(outbox, now);
            return ClaimAttempt.workedWithoutClaim();
        }

        PushNotification notification;
        try {
            notification = payloadCodec.decode(outbox).withSystemData(Map.of(
                    "notificationId", String.valueOf(outbox.getId()),
                    "schemaVersion", "1",
                    "occurredAt", outbox.getCreatedAt().toString(),
                    "audienceUserId", String.valueOf(outbox.getUserId())));
        } catch (InvalidPushPayloadException exception) {
            claimableTargets.forEach(target -> {
                target.markDead("INVALID_PAYLOAD", now);
                metrics.recordTargetFailure(
                        outbox.getNotificationType(),
                        PushTargetOutcome.TERMINAL_FAILURE,
                        "INVALID_PAYLOAD");
            });
            completeOutboxWhenFinished(outbox, now);
            log.error("Queued push payload could not be decoded: outboxId={}, type={}",
                    outbox.getId(), outbox.getNotificationType());
            return ClaimAttempt.workedWithoutClaim();
        }

        String claimToken = UUID.randomUUID().toString();
        Instant leaseUntil = now.plus(properties.claimLease());
        claimableTargets.forEach(target -> target.claim(claimToken, leaseUntil, now));
        return ClaimAttempt.claimed(new DispatchClaim(
                outbox.getId(),
                outbox.getNotificationType(),
                claimToken,
                notification,
                pushTargets));
    }

    private ClaimAttempt expandNextOutbox(Instant now) {
        List<NotificationOutbox> pending = outboxRepository.findNextForUpdate(
                NotificationOutboxStatus.PENDING,
                PageRequest.of(0, 1));
        if (pending.isEmpty()) {
            return ClaimAttempt.noWork();
        }

        NotificationOutbox outbox = pending.getFirst();
        List<UserPushToken> registrations = pushTokenRepository.findEnabledRegistrationsByUserId(outbox.getUserId());
        if (registrations.isEmpty()) {
            outbox.markCompleted(false, now);
            log.info("Push outbox completed without targets: outboxId={}, type={}",
                    outbox.getId(), outbox.getNotificationType());
            return ClaimAttempt.workedWithoutClaim();
        }

        targetRepository.saveAll(registrations.stream()
                .map(registration -> new NotificationDeliveryTarget(outbox, registration.getId(), now))
                .toList());
        outbox.markProcessing(now);
        log.info("Push outbox expanded: outboxId={}, type={}, targetCount={}",
                outbox.getId(), outbox.getNotificationType(), registrations.size());
        return ClaimAttempt.workedWithoutClaim();
    }

    private void completeClaimInTransaction(DispatchClaim claim, ClaimCompletion completion, Instant now) {
        List<Long> targetIds = claim.targets().stream()
                .map(PushTarget::deliveryTargetId)
                .toList();
        Map<Long, NotificationDeliveryTarget> targetsById = targetRepository
                .findAllByIdInForUpdate(targetIds)
                .stream()
                .collect(Collectors.toMap(NotificationDeliveryTarget::getId, Function.identity()));
        Map<Long, PushTarget> pushTargetsById = claim.targets().stream()
                .collect(Collectors.toMap(PushTarget::deliveryTargetId, Function.identity()));
        NotificationOutbox outbox = outboxRepository.findByIdForUpdate(claim.outboxId()).orElse(null);
        if (outbox == null) {
            return;
        }

        if (completion.targetResults() == null) {
            for (PushTarget pushTarget : claim.targets()) {
                NotificationDeliveryTarget target = targetsById.get(pushTarget.deliveryTargetId());
                if (target == null || !target.belongsToClaim(claim.claimToken())) {
                    continue;
                }
                if (completion.retryable()) {
                    scheduleRetryOrDead(
                            claim.notificationType(), target, completion.errorCode(), now);
                } else {
                    target.markDead(completion.errorCode(), now);
                    metrics.recordTargetFailure(
                            claim.notificationType(),
                            PushTargetOutcome.TERMINAL_FAILURE,
                            completion.errorCode());
                }
            }
        } else {
            applyResults(claim, completion.targetResults(), targetsById, pushTargetsById, now);
        }

        completeOutboxWhenFinished(outbox, now);
    }

    private void applyResults(
            DispatchClaim claim,
            List<PushTargetResult> results,
            Map<Long, NotificationDeliveryTarget> targetsById,
            Map<Long, PushTarget> pushTargetsById,
            Instant now
    ) {
        Map<Long, PushTargetResult> resultsByTargetId = new HashMap<>();
        results.forEach(result -> resultsByTargetId.put(result.deliveryTargetId(), result));

        for (PushTarget pushTarget : claim.targets()) {
            NotificationDeliveryTarget target = targetsById.get(pushTarget.deliveryTargetId());
            if (target == null || !target.belongsToClaim(claim.claimToken())) {
                continue;
            }

            PushTargetResult result = resultsByTargetId.get(target.getId());
            if (result == null) {
                scheduleRetryOrDead(
                        claim.notificationType(), target, "MISSING_FCM_RESPONSE", now);
                continue;
            }

            switch (result.outcome()) {
                case SUCCESS -> {
                    target.markSent(now);
                    metrics.recordTarget(claim.notificationType(), PushTargetOutcome.SUCCESS);
                }
                case INVALID -> {
                    target.markInvalid(result.errorCode(), now);
                    PushTarget originalTarget = pushTargetsById.get(target.getId());
                    pushTokenRepository.disableIfPushTokenMatches(
                            originalTarget.registrationId(),
                            originalTarget.pushToken(),
                            now);
                    metrics.recordTargetFailure(
                            claim.notificationType(), PushTargetOutcome.INVALID, result.errorCode());
                }
                case TERMINAL_FAILURE -> {
                    target.markDead(result.errorCode(), now);
                    metrics.recordTargetFailure(
                            claim.notificationType(), PushTargetOutcome.TERMINAL_FAILURE, result.errorCode());
                }
                case RETRYABLE_FAILURE -> scheduleRetryOrDead(
                        claim.notificationType(), target, result.errorCode(), now);
            }
        }
    }

    private void scheduleRetryOrDead(
            NotificationType notificationType,
            NotificationDeliveryTarget target,
            String errorCode,
            Instant now
    ) {
        int completedAttempts = target.getAttemptCount();
        if (completedAttempts >= properties.maxAttempts()) {
            target.markDead(errorCode, now);
            metrics.recordTargetFailure(
                    notificationType, PushTargetOutcome.TERMINAL_FAILURE, errorCode);
            return;
        }
        Duration delay = properties.retryDelay(completedAttempts, target.getId(), errorCode);
        target.scheduleRetry(errorCode, now.plus(delay), now);
        metrics.recordTargetFailure(
                notificationType, PushTargetOutcome.RETRYABLE_FAILURE, errorCode);
    }

    private void completeOutboxWhenFinished(NotificationOutbox outbox, Instant now) {
        long remaining = targetRepository.countByOutbox_IdAndStatusIn(
                outbox.getId(), CLAIMABLE_STATUSES);
        if (remaining == 0) {
            boolean hasFailedTargets = targetRepository.existsByOutbox_IdAndStatusIn(
                    outbox.getId(), FAILED_STATUSES);
            outbox.markCompleted(hasFailedTargets, now);
            if (hasFailedTargets) {
                log.warn("Push outbox completed with permanent failures: outboxId={}, type={}",
                        outbox.getId(), outbox.getNotificationType());
            } else {
                log.info("Push outbox completed: outboxId={}, type={}",
                        outbox.getId(), outbox.getNotificationType());
            }
        }
    }

    private static Map<String, Long> summarizeFailures(PushBatchResult result) {
        return result.targetResults().stream()
                .filter(targetResult -> targetResult.outcome() != PushTargetOutcome.SUCCESS)
                .collect(Collectors.groupingBy(
                        targetResult -> targetResult.outcome().name()
                                + ':'
                                + NotificationMetrics.normalizeErrorCode(targetResult.errorCode()),
                        TreeMap::new,
                        Collectors.counting()));
    }

    private record DispatchClaim(
            Long outboxId,
            NotificationType notificationType,
            String claimToken,
            PushNotification notification,
            List<PushTarget> targets
    ) {
        private DispatchClaim {
            targets = List.copyOf(targets);
        }
    }

    private record ClaimAttempt(boolean didWork, DispatchClaim claim) {
        private static ClaimAttempt noWork() {
            return new ClaimAttempt(false, null);
        }

        private static ClaimAttempt workedWithoutClaim() {
            return new ClaimAttempt(true, null);
        }

        private static ClaimAttempt claimed(DispatchClaim claim) {
            return new ClaimAttempt(true, claim);
        }
    }

    private record ClaimCompletion(
            List<PushTargetResult> targetResults,
            boolean retryable,
            String errorCode
    ) {
        private static ClaimCompletion from(PushBatchResult result) {
            return new ClaimCompletion(result.targetResults(), false, null);
        }

        private static ClaimCompletion allRetryable(String errorCode) {
            return new ClaimCompletion(null, true, errorCode);
        }

        private static ClaimCompletion allTerminal(String errorCode) {
            return new ClaimCompletion(null, false, errorCode);
        }
    }
}
