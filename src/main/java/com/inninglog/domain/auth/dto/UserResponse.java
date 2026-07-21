package com.inninglog.domain.auth.dto;

import com.inninglog.domain.user.entity.User;

public record UserResponse(
        Long id,
        String username,
        String email,
        String nickname,
        String profileImageUrl,
        Long favoriteTeamId,
        boolean onboardingCompleted
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getFavoriteTeam() == null ? null : user.getFavoriteTeam().getId(),
                user.isOnboardingCompleted());
    }
}
