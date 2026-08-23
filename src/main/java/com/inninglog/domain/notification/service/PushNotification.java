package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PushNotification(
        NotificationType type,
        String title,
        String body,
        Map<String, String> data
) {

    public PushNotification {
        Objects.requireNonNull(type, "type must not be null");
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (title.length() > 100) {
            throw new IllegalArgumentException("title must not exceed 100 characters");
        }
        if (body != null && body.length() > 500) {
            throw new IllegalArgumentException("body must not exceed 500 characters");
        }

        Map<String, String> normalizedData = new LinkedHashMap<>();
        if (data != null) {
            normalizedData.putAll(data);
        }
        normalizedData.put("type", type.name());
        data = Map.copyOf(normalizedData);
    }
}
