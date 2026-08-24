package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.repository.NotificationInboxWriter;
import com.inninglog.domain.notification.repository.NotificationInboxWriter.InsertResult;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationQueueService {

    private final UserRepository userRepository;
    private final NotificationInboxWriter inboxWriter;
    private final NotificationOutboxService outboxService;
    private final NotificationPreferencePolicy preferencePolicy;
    private final NotificationPayloadCodec payloadCodec;
    private final FcmPayloadValidator payloadValidator;
    private final Clock clock;

    public NotificationQueueService(
            UserRepository userRepository,
            NotificationInboxWriter inboxWriter,
            NotificationOutboxService outboxService,
            NotificationPreferencePolicy preferencePolicy,
            NotificationPayloadCodec payloadCodec,
            FcmPayloadValidator payloadValidator,
            Clock clock
    ) {
        this.userRepository = userRepository;
        this.inboxWriter = inboxWriter;
        this.outboxService = outboxService;
        this.preferencePolicy = preferencePolicy;
        this.payloadCodec = payloadCodec;
        this.payloadValidator = payloadValidator;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Long enqueueToUser(String idempotencyKey, Long userId, PushNotification notification) {
        String normalizedKey = requireIdempotencyKey(idempotencyKey);
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(notification, "notification must not be null");
        userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(UserNotFoundException::new);

        Instant now = clock.instant();
        PushNotification sizedNotification = notification.withSystemData(Map.of(
                "notificationId", String.valueOf(Long.MAX_VALUE),
                "schemaVersion", "1",
                "occurredAt", now.toString(),
                "audienceUserId", String.valueOf(Long.MAX_VALUE)));
        payloadValidator.validate(sizedNotification);

        InsertResult insertResult = inboxWriter.insertIfAbsent(
                normalizedKey,
                userId,
                notification.type(),
                notification.title(),
                notification.body(),
                payloadCodec.encode(notification.data()),
                now);

        if (insertResult.inserted() && preferencePolicy.isPushEnabled(userId, notification.type())) {
            outboxService.enqueue(
                    normalizedKey,
                    insertResult.notificationId(),
                    userId,
                    notification);
        }
        return insertResult.notificationId();
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
