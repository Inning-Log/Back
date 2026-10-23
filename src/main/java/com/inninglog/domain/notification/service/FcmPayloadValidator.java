package com.inninglog.domain.notification.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
public class FcmPayloadValidator {

    static final int MAX_PAYLOAD_BYTES = 4_096;

    private final ObjectMapper objectMapper;

    public FcmPayloadValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void validate(PushNotification notification) {
        Map<String, Object> messagePayload = new LinkedHashMap<>();
        messagePayload.put("data", notification.webData());
        Map<String, Object> webpush = new LinkedHashMap<>();
        // Reserve the longest TTL value used by the gateway before persisting the payload.
        webpush.put("headers", Map.of("TTL", "604800"));
        messagePayload.put("webpush", webpush);

        try {
            int payloadBytes = objectMapper.writeValueAsBytes(messagePayload).length;
            if (payloadBytes > MAX_PAYLOAD_BYTES) {
                throw new InvalidPushPayloadException(
                        "FCM payload must not exceed " + MAX_PAYLOAD_BYTES + " UTF-8 bytes.");
            }
        } catch (JacksonException exception) {
            throw new InvalidPushPayloadException("FCM payload could not be serialized.", exception);
        }
    }
}
