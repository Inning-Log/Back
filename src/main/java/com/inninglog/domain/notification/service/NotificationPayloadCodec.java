package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationOutbox;
import com.inninglog.domain.notification.entity.NotificationType;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Component
public class NotificationPayloadCodec {

    private static final TypeReference<Map<String, String>> STRING_MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final NotificationLinkService links;

    public NotificationPayloadCodec(ObjectMapper objectMapper, NotificationLinkService links) {
        this.objectMapper = objectMapper;
        this.links = links;
    }

    public String encode(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JacksonException exception) {
            throw new InvalidPushPayloadException("Notification data could not be serialized.", exception);
        }
    }

    public PushNotification decode(NotificationOutbox outbox) {
        return new PushNotification(
                outbox.getNotificationType(),
                outbox.getTitle(),
                outbox.getBody(),
                decodeData(outbox.getNotificationType(), outbox.getDataJson(), outbox.getUserId()));
    }

    public Map<String, String> decodeData(NotificationType type, String dataJson) {
        return links.forNotification(type, decodeData(dataJson));
    }

    public Map<String, String> decodeData(NotificationType type, String dataJson, long recipientUserId) {
        var data = new java.util.HashMap<>(decodeData(type, dataJson));
        if (type == NotificationType.TIMELINE_COMMENT) data.put("recipientUserId", Long.toString(recipientUserId));
        return Map.copyOf(data);
    }

    public Map<String, String> decodeData(String dataJson) {
        try {
            return Map.copyOf(objectMapper.readValue(dataJson, STRING_MAP_TYPE));
        } catch (JacksonException exception) {
            throw new InvalidPushPayloadException("Stored notification data is invalid.", exception);
        }
    }
}
