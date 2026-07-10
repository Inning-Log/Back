package com.inninglog.domain.auth.dto;

import com.inninglog.domain.user.entity.User;

public record UserResponse(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        boolean onboardingCompleted
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.isOnboardingCompleted());
    }
}
