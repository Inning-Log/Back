package com.inninglog.domain.friendship.dto;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.entity.FriendshipStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "현재 사용자 관점의 친구 관계")
public record FriendshipResponse(
        @Schema(description = "친구 관계 ID", example = "31") Long id,
        @Schema(description = "관계 상태") FriendshipStatus status,
        @Schema(description = "상대 사용자") FriendUserResponse friend,
        @Schema(description = "현재 사용자가 이 요청을 보냈는지 여부") boolean requestedByMe,
        @Schema(description = "마지막 친구 신청 시각") Instant requestedAt,
        @Schema(description = "수락 또는 거절 시각", nullable = true) Instant respondedAt
) {

    public static FriendshipResponse from(Friendship friendship, Long currentUserId) {
        return new FriendshipResponse(
                friendship.getId(),
                friendship.getStatus(),
                FriendUserResponse.from(friendship.otherUser(currentUserId)),
                friendship.isRequester(currentUserId),
                friendship.getRequestedAt(),
                friendship.getRespondedAt());
    }
}
