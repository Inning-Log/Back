package com.inninglog.domain.auth.dto;

import com.inninglog.domain.auth.service.AuthSessionService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "회전된 서비스 인증 토큰")
public record TokenPairResponse(
        String tokenType,
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt
) {

    public static TokenPairResponse from(AuthSessionService.SessionTokens tokens) {
        return new TokenPairResponse(
                tokens.accessToken().tokenType(),
                tokens.accessToken().accessToken(),
                tokens.accessToken().expiresAt(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresAt());
    }
}
