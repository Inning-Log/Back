package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationType;
import java.net.URI;
import java.net.URISyntaxException;
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
            data.forEach(PushNotification::validateDataEntry);
            normalizedData.putAll(data);
        }
        validateLink(normalizedData.get("link"));
        normalizedData.put("type", type.name());
        data = Map.copyOf(normalizedData);
    }

    public PushNotification withSystemData(Map<String, String> systemData) {
        Map<String, String> mergedData = new LinkedHashMap<>(data);
        mergedData.putAll(systemData);
        return new PushNotification(type, title, body, mergedData);
    }

    private static void validateDataEntry(String key, String value) {
        if (key == null || key.isBlank()) {
            throw new InvalidPushPayloadException("FCM data keys must not be blank.");
        }
        if (value == null) {
            throw new InvalidPushPayloadException("FCM data values must not be null.");
        }

        String normalizedKey = key.toLowerCase(java.util.Locale.ROOT);
        if (normalizedKey.equals("from")
                || normalizedKey.equals("message_type")
                || normalizedKey.startsWith("google.")
                || normalizedKey.startsWith("gcm.")) {
            throw new InvalidPushPayloadException("FCM data key is reserved: " + key);
        }
    }

    private static void validateLink(String link) {
        if (link == null) {
            return;
        }
        try {
            URI uri = new URI(link);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new InvalidPushPayloadException("FCM link must be an absolute HTTPS URL.");
            }
        } catch (URISyntaxException exception) {
            throw new InvalidPushPayloadException("FCM link must be an absolute HTTPS URL.", exception);
        }
    }
}
