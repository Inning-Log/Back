package com.inninglog.domain.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record NotificationListResponse(
        List<NotificationItemResponse> items,
        @Schema(description = "다음 페이지 cursor. 마지막 페이지면 null", nullable = true) Long nextCursor,
        boolean hasNext,
        @Schema(description = "페이지와 무관한 전체 미읽음 개수. 수락·거절·취소 등 처리된 친구 신청은 제외") long unreadCount
) {
}
