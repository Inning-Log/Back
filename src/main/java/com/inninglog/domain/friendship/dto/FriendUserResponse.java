package com.inninglog.domain.friendship.dto;

import com.inninglog.domain.team.dto.TeamSummaryResponse;
import com.inninglog.domain.user.entity.User;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "친구 기능에서 노출하는 최소 사용자 프로필")
public record FriendUserResponse(
        @Schema(description = "사용자 ID", example = "12") Long id,
        @Schema(description = "서비스 아이디", example = "inninglog") String username,
        @Schema(description = "닉네임", example = "이닝로그") String nickname,
        @Schema(description = "프로필 이미지 URL", nullable = true) String profileImageUrl,
        @Schema(description = "응원팀", nullable = true) TeamSummaryResponse favoriteTeam
) {

    public static FriendUserResponse from(User user) {
        return new FriendUserResponse(
                user.getId(),
                user.getUsername(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getFavoriteTeam() == null ? null : TeamSummaryResponse.from(user.getFavoriteTeam()));
    }
}
