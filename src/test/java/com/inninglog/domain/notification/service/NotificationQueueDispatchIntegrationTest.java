package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inninglog.domain.notification.dto.PushTokenRegistrationRequest;
import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.entity.NotificationDeliveryTarget;
import com.inninglog.domain.notification.entity.NotificationOutbox;
import com.inninglog.domain.notification.entity.NotificationOutboxStatus;
import com.inninglog.domain.notification.entity.NotificationTargetStatus;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.entity.UserPushToken;
import com.inninglog.domain.notification.repository.NotificationDeliveryTargetRepository;
import com.inninglog.domain.notification.repository.NotificationOutboxRepository;
import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest(properties = {
        "app.firebase.dispatch.initial-backoff=1ms",
        "app.firebase.dispatch.max-backoff=1s",
        "app.firebase.dispatch.max-attempts=2",
        "app.firebase.dispatch.claim-lease=1ms"
})
@ActiveProfiles("test")
@Import(NotificationQueueDispatchIntegrationTest.GatewayTestConfiguration.class)
class NotificationQueueDispatchIntegrationTest {

    @Autowired
    private NotificationQueueService queueService;

    @Autowired
    private NotificationDispatchService dispatchService;

    @Autowired
    private PushTokenRegistrationService registrationService;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationDeliveryTargetRepository targetRepository;

    @Autowired
    private UserPushTokenRepository pushTokenRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private ControllablePushGateway pushGateway;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void clearNotificationState() {
        targetRepository.deleteAll();
        outboxRepository.deleteAll();
        pushTokenRepository.deleteAll();
        pushGateway.reset();
    }

    @Test
    void enqueueRollsBackWithTheBusinessTransaction() {
        User user = createUser("outbox-rollback@example.com");
        double metricBefore = enqueuedMetric();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(ignored -> {
            queueService.enqueueToUser("rollback-event", user.getId(), notification());
            throw new IllegalStateException("business rollback");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(outboxRepository.count()).isZero();
        assertThat(enqueuedMetric()).isEqualTo(metricBefore);
    }

    @Test
    void enqueueRequiresAnExistingBusinessTransaction() {
        User user = createUser("outbox-no-transaction@example.com");

        assertThatThrownBy(() -> queueService.enqueueToUser(
                "missing-business-transaction", user.getId(), notification()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void queueDepthMetricsIncludeTheActualPendingAndProcessingBacklog() {
        assertThat(meterRegistry.find("inninglog.push.queue.depth")
                .tag("queue", "outbox")
                .tag("status", "processing")
                .gauge()).isNotNull();
        assertThat(meterRegistry.find("inninglog.push.queue.depth")
                .tag("queue", "target")
                .tag("status", "pending")
                .gauge()).isNotNull();
    }

    @Test
    void concurrentIdempotentEnqueueCreatesOneOutboxRow() throws Exception {
        User user = createUser("outbox-concurrent@example.com");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> enqueueConcurrently(ready, start, user.getId()));
            Future<Long> second = executor.submit(() -> enqueueConcurrently(ready, start, user.getId()));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(first.get()).isEqualTo(second.get());
        }

        assertThat(outboxRepository.count()).isEqualTo(1);
    }

    @Test
    void theSameDomainEventCanBeEnqueuedOnceForEachUser() {
        User firstUser = createUser("outbox-recipient-a@example.com");
        User secondUser = createUser("outbox-recipient-b@example.com");
        double metricBefore = enqueuedMetric();

        Long firstOutboxId = enqueue("shared-domain-event", firstUser.getId());
        Long duplicateOutboxId = enqueue("shared-domain-event", firstUser.getId());
        Long secondOutboxId = enqueue("shared-domain-event", secondUser.getId());

        assertThat(duplicateOutboxId).isEqualTo(firstOutboxId);
        assertThat(secondOutboxId).isNotEqualTo(firstOutboxId);
        assertThat(outboxRepository.count()).isEqualTo(2);
        assertThat(enqueuedMetric()).isEqualTo(metricBefore + 2.0d);
    }

    @Test
    void unrelatedIdempotencyKeysDoNotSerializeBusinessTransactions() throws Exception {
        User user = createUser("outbox-independent@example.com");
        CountDownLatch firstInsertCompleted = new CountDownLatch(1);
        CountDownLatch releaseFirstTransaction = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> first = executor.submit(() -> transactionTemplate.execute(ignored -> {
                Long id = queueService.enqueueToUser("independent-event-a", user.getId(), notification());
                firstInsertCompleted.countDown();
                await(releaseFirstTransaction);
                return id;
            }));

            assertThat(firstInsertCompleted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Long> second = executor.submit(() -> transactionTemplate.execute(ignored ->
                    queueService.enqueueToUser("independent-event-b", user.getId(), notification())));

            try {
                assertThat(second.get(2, TimeUnit.SECONDS)).isNotNull();
            } finally {
                releaseFirstTransaction.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        }

        assertThat(outboxRepository.count()).isEqualTo(2);
    }

    @Test
    void dispatchCallsGatewayOutsideTransactionAndCompletesSuccessfully() throws Exception {
        User user = createUserWithRegistration("dispatch-success@example.com", "success-fid", "success-token");
        Long outboxId = enqueue("success-event", user.getId());

        assertThat(dispatchService.dispatchNext()).isTrue();
        assertThat(dispatchWithinOneSecond()).isTrue();

        NotificationOutbox outbox = outboxRepository.findById(outboxId).orElseThrow();
        NotificationDeliveryTarget target = targetRepository.findAll().getFirst();
        assertThat(pushGateway.transactionActiveDuringSend.get()).isFalse();
        assertThat(pushGateway.calls.get()).isEqualTo(1);
        assertThat(target.getStatus()).isEqualTo(NotificationTargetStatus.SENT);
        assertThat(target.getAttemptCount()).isEqualTo(1);
        assertThat(outbox.getStatus()).isEqualTo(NotificationOutboxStatus.COMPLETED);
    }

    @Test
    void retryableFailureRetriesOnlyTheTargetAndThenSucceeds() throws Exception {
        User user = createUserWithRegistration("dispatch-retry@example.com", "retry-fid", "retry-token");
        Long outboxId = enqueue("retry-event", user.getId());
        pushGateway.outcome = PushTargetOutcome.RETRYABLE_FAILURE;

        assertThat(dispatchService.dispatchNext()).isTrue();
        assertThat(dispatchWithinOneSecond()).isTrue();
        assertThat(targetRepository.findAll().getFirst().getStatus()).isEqualTo(NotificationTargetStatus.RETRY);

        Thread.sleep(10);
        pushGateway.outcome = PushTargetOutcome.SUCCESS;
        assertThat(dispatchWithinOneSecond()).isTrue();

        NotificationDeliveryTarget target = targetRepository.findAll().getFirst();
        assertThat(target.getStatus()).isEqualTo(NotificationTargetStatus.SENT);
        assertThat(target.getAttemptCount()).isEqualTo(2);
        assertThat(outboxRepository.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.COMPLETED);
    }

    @Test
    void retryableFailureBecomesDeadAtTheConfiguredAttemptLimit() throws Exception {
        User user = createUserWithRegistration("dispatch-dead@example.com", "dead-fid", "dead-token");
        Long outboxId = enqueue("dead-event", user.getId());
        pushGateway.outcome = PushTargetOutcome.RETRYABLE_FAILURE;

        assertThat(dispatchService.dispatchNext()).isTrue();
        assertThat(dispatchWithinOneSecond()).isTrue();
        Thread.sleep(10);
        assertThat(dispatchWithinOneSecond()).isTrue();

        NotificationDeliveryTarget target = targetRepository.findAll().getFirst();
        assertThat(target.getStatus()).isEqualTo(NotificationTargetStatus.DEAD);
        assertThat(target.getAttemptCount()).isEqualTo(2);
        assertThat(outboxRepository.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.COMPLETED_WITH_FAILURES);
    }

    @Test
    void ownershipChangeCancelsTheOldUsersPendingTarget() throws Exception {
        User oldUser = createUserWithRegistration(
                "dispatch-old-owner@example.com", "ownership-fid", "ownership-token-a");
        User newUser = createUser("dispatch-new-owner@example.com");
        Long outboxId = enqueue("ownership-event", oldUser.getId());

        assertThat(dispatchService.dispatchNext()).isTrue();
        registrationService.register(
                newUser.getId().toString(),
                new PushTokenRegistrationRequest(DevicePlatform.ANDROID, "ownership-fid", "ownership-token-b"));
        assertThat(dispatchWithinOneSecond()).isTrue();

        assertThat(pushGateway.calls.get()).isZero();
        assertThat(targetRepository.findAll().getFirst().getStatus())
                .isEqualTo(NotificationTargetStatus.CANCELLED_OWNERSHIP);
        assertThat(outboxRepository.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.COMPLETED);
    }

    @Test
    void invalidResponseForAnOldTokenDoesNotDisableTheRotatedToken() throws Exception {
        User user = createUserWithRegistration("dispatch-rotation@example.com", "rotation-fid", "rotation-token-a");
        enqueue("rotation-event", user.getId());
        pushGateway.outcome = PushTargetOutcome.INVALID;
        pushGateway.beforeResponse = () -> registrationService.register(
                user.getId().toString(),
                new PushTokenRegistrationRequest(DevicePlatform.ANDROID, "rotation-fid", "rotation-token-b"));

        assertThat(dispatchService.dispatchNext()).isTrue();
        assertThat(dispatchWithinOneSecond()).isTrue();

        UserPushToken registration = pushTokenRepository.findByDeviceId("rotation-fid").orElseThrow();
        assertThat(registration.getPushToken()).isEqualTo("rotation-token-b");
        assertThat(registration.isEnabled()).isTrue();
        assertThat(targetRepository.findAll().getFirst().getStatus())
                .isEqualTo(NotificationTargetStatus.INVALID);
    }

    @Test
    void expiredWorkerClaimsAreBoundedByTheMaximumAttemptCount() throws Exception {
        User user = createUserWithRegistration("dispatch-crash@example.com", "crash-fid", "crash-token");
        Long outboxId = enqueue("crash-event", user.getId());

        assertThat(dispatchService.dispatchNext()).isTrue();
        pushGateway.beforeResponse = () -> {
            throw new SimulatedWorkerCrash();
        };

        Thread.sleep(10);
        assertThatThrownBy(() -> dispatchService.dispatchNext())
                .isInstanceOf(SimulatedWorkerCrash.class);
        assertThat(targetRepository.findAll().getFirst().getAttemptCount()).isEqualTo(1);

        Thread.sleep(10);
        assertThatThrownBy(() -> dispatchService.dispatchNext())
                .isInstanceOf(SimulatedWorkerCrash.class);
        assertThat(targetRepository.findAll().getFirst().getAttemptCount()).isEqualTo(2);

        pushGateway.beforeResponse = () -> {
        };
        Thread.sleep(10);
        assertThat(dispatchWithinOneSecond()).isTrue();

        assertThat(pushGateway.calls.get()).isEqualTo(2);
        assertThat(targetRepository.findAll().getFirst().getStatus())
                .isEqualTo(NotificationTargetStatus.DEAD);
        assertThat(outboxRepository.findById(outboxId).orElseThrow().getStatus())
                .isEqualTo(NotificationOutboxStatus.COMPLETED_WITH_FAILURES);
    }

    private Long enqueueConcurrently(CountDownLatch ready, CountDownLatch start, Long userId) throws Exception {
        ready.countDown();
        start.await();
        return transactionTemplate.execute(ignored ->
                queueService.enqueueToUser("same-domain-event", userId, notification()));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for the test latch.");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the test latch.", exception);
        }
    }

    private Long enqueue(String key, Long userId) {
        return transactionTemplate.execute(ignored -> queueService.enqueueToUser(key, userId, notification()));
    }

    private boolean dispatchWithinOneSecond() throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (dispatchService.dispatchNext()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private double enqueuedMetric() {
        var counter = meterRegistry.find("inninglog.push.outbox.total")
                .tag("type", NotificationType.GAME_INNING_STARTED.name())
                .tag("outcome", "enqueued")
                .counter();
        return counter == null ? 0.0d : counter.count();
    }

    private User createUser(String email) {
        return userRepository.save(new User(email, null));
    }

    private User createUserWithRegistration(String email, String deviceId, String pushToken) {
        User user = createUser(email);
        registrationService.register(
                user.getId().toString(),
                new PushTokenRegistrationRequest(DevicePlatform.ANDROID, deviceId, pushToken));
        return user;
    }

    private PushNotification notification() {
        return new PushNotification(
                NotificationType.GAME_INNING_STARTED,
                "1회가 시작되었습니다!",
                "경기 진행 상황을 확인해보세요.",
                Map.of("gameId", "42"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayTestConfiguration {

        @Bean
        @Primary
        ControllablePushGateway controllablePushGateway() {
            return new ControllablePushGateway();
        }
    }

    static class ControllablePushGateway implements PushGateway {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicBoolean transactionActiveDuringSend = new AtomicBoolean();
        private volatile PushTargetOutcome outcome = PushTargetOutcome.SUCCESS;
        private volatile Runnable beforeResponse = () -> {
        };

        @Override
        public PushBatchResult send(PushNotification notification, List<PushTarget> targets) {
            calls.incrementAndGet();
            transactionActiveDuringSend.set(TransactionSynchronizationManager.isActualTransactionActive());
            beforeResponse.run();
            return new PushBatchResult(targets.stream()
                    .map(target -> outcome == PushTargetOutcome.SUCCESS
                            ? PushTargetResult.success(target)
                            : PushTargetResult.failure(target, outcome, errorCode(outcome)))
                    .toList());
        }

        void reset() {
            calls.set(0);
            transactionActiveDuringSend.set(false);
            outcome = PushTargetOutcome.SUCCESS;
            beforeResponse = () -> {
            };
        }

        private static String errorCode(PushTargetOutcome outcome) {
            return switch (outcome) {
                case INVALID -> "UNREGISTERED";
                case RETRYABLE_FAILURE -> "UNAVAILABLE";
                case TERMINAL_FAILURE -> "INVALID_ARGUMENT";
                case SUCCESS -> throw new IllegalArgumentException("Success does not have an error code.");
            };
        }
    }

    static class SimulatedWorkerCrash extends Error {
    }
}
