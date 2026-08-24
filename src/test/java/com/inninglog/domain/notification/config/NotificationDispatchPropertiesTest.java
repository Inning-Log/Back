package com.inninglog.domain.notification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationDispatchPropertiesTest {

    @Test
    void quotaExceededWaitsAtLeastOneMinuteEvenWhenTheConfiguredCapIsLower() {
        NotificationDispatchProperties properties = new NotificationDispatchProperties(
                100,
                20,
                5,
                Duration.ofSeconds(1),
                Duration.ofSeconds(10),
                Duration.ofMinutes(5));

        assertThat(properties.retryDelay(1, 1L, "QUOTA_EXCEEDED"))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(1));
        assertThat(properties.retryDelay(1, 1L, "RESOURCE_EXHAUSTED"))
                .isGreaterThanOrEqualTo(Duration.ofMinutes(1));
    }
}
