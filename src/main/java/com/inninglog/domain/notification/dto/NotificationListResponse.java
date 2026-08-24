package com.inninglog.domain.notification.dto;

import java.util.List;

public record NotificationListResponse(
        List<NotificationItemResponse> items,
        Long nextCursor,
        boolean hasNext,
        long unreadCount
) {
}
