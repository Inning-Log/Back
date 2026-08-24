package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.NotificationType;
import java.time.Instant;
import java.util.Map;

public record NotificationItemResponse(
        Long id,
        NotificationType type,
        String title,
        String body,
        Map<String, String> data,
        Instant readAt,
        Instant createdAt
) {
}
