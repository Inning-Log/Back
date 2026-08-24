package com.inninglog.domain.notification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.firebase.dispatch")
public record NotificationDispatchProperties(
        int batchSize,
        int maxDispatchesPerTick,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration claimLease
) {

    public NotificationDispatchProperties {
        if (batchSize <= 0 || batchSize > 500) {
            batchSize = 100;
        }
        if (maxDispatchesPerTick <= 0) {
            maxDispatchesPerTick = 20;
        }
        if (maxAttempts <= 0) {
            maxAttempts = 5;
        }
        if (initialBackoff == null || initialBackoff.isNegative() || initialBackoff.isZero()) {
            initialBackoff = Duration.ofSeconds(10);
        }
        if (maxBackoff == null || maxBackoff.compareTo(initialBackoff) < 0) {
            maxBackoff = Duration.ofHours(1);
        }
        if (claimLease == null || claimLease.isNegative() || claimLease.isZero()) {
            claimLease = Duration.ofMinutes(5);
        }
    }

    public Duration retryDelay(int completedAttempts, long targetId, String errorCode) {
        int exponent = Math.max(0, Math.min(completedAttempts - 1, 20));
        long multiplier = 1L << exponent;
        long baseMillis;
        try {
            baseMillis = Math.multiplyExact(initialBackoff.toMillis(), multiplier);
        } catch (ArithmeticException exception) {
            baseMillis = maxBackoff.toMillis();
        }
        baseMillis = Math.min(baseMillis, maxBackoff.toMillis());

        long jitterBasisPoints = 8_000L + Math.floorMod(targetId * 31L + completedAttempts * 17L, 4_001L);
        long jitteredMillis = Math.max(1L, baseMillis * jitterBasisPoints / 10_000L);
        if ("QUOTA_EXCEEDED".equals(errorCode) || "RESOURCE_EXHAUSTED".equals(errorCode)) {
            return Duration.ofMillis(Math.max(
                    jitteredMillis,
                    Duration.ofMinutes(1).toMillis()));
        }
        return Duration.ofMillis(Math.min(jitteredMillis, maxBackoff.toMillis()));
    }
}
