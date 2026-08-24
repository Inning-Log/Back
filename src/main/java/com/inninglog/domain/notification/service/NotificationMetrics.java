package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationOutboxStatus;
import com.inninglog.domain.notification.entity.NotificationTargetStatus;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.repository.NotificationDeliveryTargetRepository;
import com.inninglog.domain.notification.repository.NotificationOutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final MeterRegistry meterRegistry;

    public NotificationMetrics(
            MeterRegistry meterRegistry,
            NotificationOutboxRepository outboxRepository,
            NotificationDeliveryTargetRepository targetRepository
    ) {
        this.meterRegistry = meterRegistry;
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        outboxRepository,
                        repository -> repository.countByStatus(NotificationOutboxStatus.PENDING))
                .tag("queue", "outbox")
                .tag("status", "pending")
                .register(meterRegistry);
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        outboxRepository,
                        repository -> repository.countByStatus(NotificationOutboxStatus.PROCESSING))
                .tag("queue", "outbox")
                .tag("status", "processing")
                .register(meterRegistry);
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        targetRepository,
                        repository -> repository.countByStatus(NotificationTargetStatus.PENDING))
                .tag("queue", "target")
                .tag("status", "pending")
                .register(meterRegistry);
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        targetRepository,
                        repository -> repository.countByStatus(NotificationTargetStatus.PROCESSING))
                .tag("queue", "target")
                .tag("status", "processing")
                .register(meterRegistry);
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        targetRepository,
                        repository -> repository.countByStatus(NotificationTargetStatus.RETRY))
                .tag("queue", "target")
                .tag("status", "retry")
                .register(meterRegistry);
        Gauge.builder(
                        "inninglog.push.queue.depth",
                        targetRepository,
                        repository -> repository.countByStatus(NotificationTargetStatus.DEAD))
                .tag("queue", "target")
                .tag("status", "dead")
                .register(meterRegistry);
    }

    public void recordEnqueued(NotificationType type) {
        counter("inninglog.push.outbox.total", type, "enqueued").increment();
    }

    public void recordTarget(NotificationType type, PushTargetOutcome outcome) {
        counter("inninglog.push.target.total", type, outcome.name().toLowerCase()).increment();
    }

    public void recordTargetFailure(NotificationType type, PushTargetOutcome outcome, String errorCode) {
        recordTarget(type, outcome);
        Counter.builder("inninglog.push.failure.total")
                .tag("type", type.name())
                .tag("outcome", outcome.name().toLowerCase())
                .tag("error_code", normalizeErrorCode(errorCode))
                .register(meterRegistry)
                .increment();
    }

    public void recordCancelledOwnership(NotificationType type) {
        counter("inninglog.push.target.total", type, "cancelled_ownership").increment();
    }

    public void recordDispatchDuration(NotificationType type, Duration duration) {
        Timer.builder("inninglog.push.delivery.duration")
                .tag("type", type.name())
                .register(meterRegistry)
                .record(duration);
    }

    private Counter counter(String name, NotificationType type, String outcome) {
        return Counter.builder(name)
                .tag("type", type.name())
                .tag("outcome", outcome)
                .register(meterRegistry);
    }

    static String normalizeErrorCode(String errorCode) {
        if (errorCode == null) {
            return "UNKNOWN";
        }
        return switch (errorCode) {
            case "UNREGISTERED",
                    "INVALID_ARGUMENT",
                    "SENDER_ID_MISMATCH",
                    "THIRD_PARTY_AUTH_ERROR",
                    "QUOTA_EXCEEDED",
                    "RESOURCE_EXHAUSTED",
                    "UNAVAILABLE",
                    "INTERNAL",
                    "DEADLINE_EXCEEDED",
                    "ABORTED",
                    "CANCELLED",
                    "UNAUTHENTICATED",
                    "PERMISSION_DENIED",
                    "NOT_FOUND",
                    "FAILED_PRECONDITION",
                    "OUT_OF_RANGE",
                    "ALREADY_EXISTS",
                    "DATA_LOSS",
                    "UNKNOWN",
                    "MISSING_FCM_RESPONSE",
                    "DISPATCH_EXCEPTION",
                    "INVALID_PAYLOAD",
                    "CLAIM_LEASE_EXHAUSTED",
                    "FIREBASE_DISABLED" -> errorCode;
            default -> "OTHER";
        };
    }
}
