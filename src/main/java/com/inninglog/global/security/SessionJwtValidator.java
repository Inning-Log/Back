package com.inninglog.global.security;

import com.inninglog.domain.auth.repository.AuthRefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SessionJwtValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_SESSION = new OAuth2Error(
            "invalid_token",
            "The login session is invalid, expired, rotated, revoked, or deleted.",
            null);

    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final Clock clock;
    private final boolean devTokenEnabled;

    public SessionJwtValidator(
            AuthRefreshTokenRepository refreshTokenRepository,
            Clock clock,
            @Value("${app.auth.dev-token-enabled:false}") boolean devTokenEnabled
    ) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.clock = clock;
        this.devTokenEnabled = devTokenEnabled;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        if (devTokenEnabled && Boolean.TRUE.equals(jwt.getClaimAsBoolean("dev"))) {
            return OAuth2TokenValidatorResult.success();
        }

        Long userId = parseLong(jwt.getSubject());
        Long sessionId = numberClaim(jwt, "sid");
        Long generation = numberClaim(jwt, "sessionGeneration");
        if (userId == null || sessionId == null || generation == null) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        AuthRefreshTokenRepository.SessionState state = refreshTokenRepository
                .findSessionState(sessionId)
                .orElse(null);
        Instant now = clock.instant();
        if (state == null
                || !userId.equals(state.getUserId())
                || state.getUserDeletedAt() != null
                || state.getRevokedAt() != null
                || state.getExpiresAt() == null
                || !state.getExpiresAt().isAfter(now)
                || state.getGeneration() == null
                || generation.intValue() != state.getGeneration()) {
            return OAuth2TokenValidatorResult.failure(INVALID_SESSION);
        }

        return OAuth2TokenValidatorResult.success();
    }

    private static Long numberClaim(Jwt jwt, String name) {
        Object value = jwt.getClaim(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Long parseLong(String value) {
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
