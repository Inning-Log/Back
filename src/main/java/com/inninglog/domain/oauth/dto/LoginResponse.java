package com.inninglog.domain.oauth.dto;

import com.inninglog.domain.auth.service.AuthSessionService;
import com.inninglog.domain.user.dto.UserResponse;
import java.time.Instant;

public record LoginResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        String refreshToken,
        Instant refreshTokenExpiresAt,
        boolean isNewUser,
        UserResponse user
) {

    public static LoginResponse of(
            AuthSessionService.SessionTokens tokens,
            boolean isNewUser,
            UserResponse user
    ) {
        return new LoginResponse(
                tokens.accessToken().tokenType(),
                tokens.accessToken().accessToken(),
                tokens.accessToken().expiresAt(),
                tokens.refreshToken(),
                tokens.refreshTokenExpiresAt(),
                isNewUser,
                user);
    }
}
