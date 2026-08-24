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
public class NotificationOutboxService {

    private final NotificationOutboxWriter outboxWriter;
    private final NotificationPayloadCodec payloadCodec;
    private final FcmPayloadValidator payloadValidator;
    private final NotificationMetrics metrics;
    private final Clock clock;

    public NotificationOutboxService(
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
    public Long enqueue(
            String idempotencyKey,
            Long notificationId,
            Long userId,
            PushNotification notification
    ) {
        Objects.requireNonNull(notificationId, "notificationId must not be null");
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
                idempotencyKey,
                notificationId,
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
}
