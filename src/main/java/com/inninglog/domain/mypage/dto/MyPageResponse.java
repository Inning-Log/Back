package com.inninglog.domain.mypage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.user.entity.User;

public record MyPageResponse(
        Long id,
        @Schema(description = "닉네임 설정 전 null", nullable = true) String nickname,
        @Schema(description = "아이디 설정 전 null", nullable = true) String username,
        String email,
        @Schema(description = "프로필 사진 미설정 시 null", nullable = true) String profileImageUrl,
        @Schema(description = "응원팀 설정 전 null", nullable = true) TeamSummaryResponse favoriteTeam
) {

    public static MyPageResponse from(User user) {
        return new MyPageResponse(
                user.getId(),
                user.getNickname(),
                user.getUsername(),
                user.getEmail(),
                user.getProfileImageUrl(),
                user.getFavoriteTeam() == null ? null : TeamSummaryResponse.from(user.getFavoriteTeam()));
    }
}
