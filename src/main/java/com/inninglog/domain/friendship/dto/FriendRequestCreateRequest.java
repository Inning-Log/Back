package com.inninglog.domain.friendship.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

@Schema(description = "친구 신청 생성 요청")
public record FriendRequestCreateRequest(
        @Schema(description = "검색 결과에서 선택한 상대 사용자 ID", example = "12")
        @NotNull @Positive Long receiverId
) {
}
