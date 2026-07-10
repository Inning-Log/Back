package com.inninglog.domain.auth.dto;

import com.inninglog.global.security.JwtTokenProvider;
import java.time.Instant;

public record LoginResponse(
        String tokenType,
        String accessToken,
        Instant expiresAt,
        boolean isNewUser,
        UserResponse user
) {

    public static LoginResponse of(JwtTokenProvider.IssuedToken token, boolean isNewUser, UserResponse user) {
        return new LoginResponse(
                token.tokenType(),
                token.accessToken(),
                token.expiresAt(),
                isNewUser,
                user);
    }
}
