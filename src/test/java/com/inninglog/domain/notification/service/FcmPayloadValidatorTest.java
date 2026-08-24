package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inninglog.domain.notification.entity.NotificationType;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class FcmPayloadValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FcmPayloadValidator validator = new FcmPayloadValidator(objectMapper);

    @Test
    void acceptsAValidMultibytePayload() {
        PushNotification notification = new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                "9회가 끝나기 전에 기록해주세요.",
                Map.of("gameId", "42", "deepLink", "inninglog://games/42/record"));

        assertThatCode(() -> validator.validate(notification)).doesNotThrowAnyException();
    }

    @Test
    void rejectsReservedDataKeys() {
        assertThatThrownBy(() -> new PushNotification(
                NotificationType.TIMELINE_REACTION,
                "친구 알림",
                "친구가 반응했습니다.",
                Map.of("google.analytics", "forbidden")))
                .isInstanceOf(InvalidPushPayloadException.class)
                .hasMessageContaining("reserved");
    }

    @Test
    void rejectsPayloadsLargerThanTheFcmUtf8Limit() {
        PushNotification notification = new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                null,
                Map.of("content", "가".repeat(1_400)));

        assertThatThrownBy(() -> validator.validate(notification))
                .isInstanceOf(InvalidPushPayloadException.class)
                .hasMessageContaining("4096");
    }

    @Test
    void acceptsExactly4096Utf8BytesAndRejectsTheNextMultibyteCharacter() throws Exception {
        PushNotification empty = new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                null,
                Map.of("content", ""));
        int baseBytes = serializedBytes(empty);
        int availableBytes = FcmPayloadValidator.MAX_PAYLOAD_BYTES - baseBytes;
        String exactValue = "가".repeat(availableBytes / 3) + "a".repeat(availableBytes % 3);
        PushNotification exact = new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                null,
                Map.of("content", exactValue));

        assertThatCode(() -> validator.validate(exact)).doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                null,
                Map.of("content", exactValue + "⚾"))))
                .isInstanceOf(InvalidPushPayloadException.class);
    }

    private int serializedBytes(PushNotification notification) throws Exception {
        return objectMapper.writeValueAsBytes(Map.of(
                "notification", Map.of("title", notification.title()),
                "data", notification.data())).length;
    }
}
