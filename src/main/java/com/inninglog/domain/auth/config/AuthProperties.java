package com.inninglog.domain.auth.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
        boolean devTokenEnabled,
        Duration refreshTokenExpiration
) {

    public AuthProperties {
        if (refreshTokenExpiration == null || refreshTokenExpiration.isZero() || refreshTokenExpiration.isNegative()) {
            throw new IllegalArgumentException("app.auth.refresh-token-expiration must be a positive duration.");
        }
    }
}
