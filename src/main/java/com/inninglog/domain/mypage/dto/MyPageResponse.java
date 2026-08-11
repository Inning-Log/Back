package com.inninglog.domain.mypage.dto;

import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.user.entity.User;

public record MyPageResponse(
        Long id,
        String nickname,
        String username,
        String email,
        String profileImageUrl,
        TeamSummaryResponse favoriteTeam
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
