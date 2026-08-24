package com.inninglog.domain.friendship.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "친구 추가용 사용자 검색 결과")
public record UserSearchResponse(
        FriendUserResponse user,
        @Schema(description = "현재 사용자와의 관계 상태") FriendRelationshipStatus relationshipStatus,
        @Schema(description = "기존 관계 ID. 관계가 없으면 null", nullable = true) Long friendshipId
) {
}
