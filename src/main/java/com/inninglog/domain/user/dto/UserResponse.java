package com.inninglog.domain.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.user.entity.User;

public record UserResponse(
        Long id,
        @Schema(description = "아이디 설정 전 null", nullable = true) String username,
        String email,
        @Schema(description = "닉네임 설정 전 null", nullable = true) String nickname,
        @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl,
        @Schema(description = "응원팀 설정 전 null", nullable = true) Long favoriteTeamId,
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
