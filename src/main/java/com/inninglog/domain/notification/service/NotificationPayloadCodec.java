package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationOutbox;
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

    public NotificationPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(Map<String, String> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JacksonException exception) {
            throw new InvalidPushPayloadException("Notification data could not be serialized.", exception);
        }
    }

    public PushNotification decode(NotificationOutbox outbox) {
        try {
            Map<String, String> data = objectMapper.readValue(outbox.getDataJson(), STRING_MAP_TYPE);
            return new PushNotification(
                    outbox.getNotificationType(),
                    outbox.getTitle(),
                    outbox.getBody(),
                    data);
        } catch (JacksonException exception) {
            throw new InvalidPushPayloadException("Queued notification data is invalid.", exception);
        }
    }
}
