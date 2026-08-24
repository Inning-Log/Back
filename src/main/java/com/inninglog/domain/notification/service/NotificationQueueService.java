package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.repository.NotificationOutboxWriter;
import com.inninglog.domain.notification.repository.NotificationOutboxWriter.InsertResult;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class NotificationQueueService {

    private final NotificationOutboxWriter outboxWriter;
    private final NotificationPayloadCodec payloadCodec;
    private final FcmPayloadValidator payloadValidator;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public NotificationQueueService(
            NotificationOutboxWriter outboxWriter,
            NotificationPayloadCodec payloadCodec,
            FcmPayloadValidator payloadValidator,
            NotificationMetrics metrics,
            Clock clock
    ) {
        this.outboxWriter = outboxWriter;
        this.payloadCodec = payloadCodec;
        this.payloadValidator = payloadValidator;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueToUser(String idempotencyKey, Long userId, PushNotification notification) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(notification, "notification must not be null");

        Instant now = clock.instant();
        PushNotification sizedNotification = notification.withSystemData(Map.of(
                "notificationId", String.valueOf(Long.MAX_VALUE),
                "schemaVersion", "1",
                "occurredAt", now.toString(),
                "audienceUserId", String.valueOf(Long.MAX_VALUE)));
        payloadValidator.validate(sizedNotification);

        InsertResult insertResult = outboxWriter.insertIfAbsent(
                normalizedKey,
                userId,
                notification.type(),
                notification.title(),
                notification.body(),
                payloadCodec.encode(notification.data()),
                now);

        if (insertResult.inserted()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    metrics.recordEnqueued(notification.type());
                }
            });
        }
        return insertResult.outboxId();
    }

    private static String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        String normalized = idempotencyKey.trim();
        if (normalized.length() > 200) {
            throw new IllegalArgumentException("idempotencyKey must not exceed 200 characters");
        }
        return normalized;
    }
}
