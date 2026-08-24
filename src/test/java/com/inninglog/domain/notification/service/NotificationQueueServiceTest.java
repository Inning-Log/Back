package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.repository.NotificationOutboxWriter;
import com.inninglog.domain.notification.repository.NotificationOutboxWriter.InsertResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationQueueServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-24T04:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NotificationOutboxWriter outboxWriter = mock(NotificationOutboxWriter.class);
    private final NotificationMetrics metrics = mock(NotificationMetrics.class);
    private final NotificationOutboxService outboxService = new NotificationOutboxService(
            outboxWriter,
            new NotificationPayloadCodec(objectMapper),
            new FcmPayloadValidator(objectMapper),
            metrics,
            Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

    @Test
    void queueLimitIncludesAllServerAddedFieldsAtThe4096ByteBoundary() throws Exception {
        when(outboxWriter.insertIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new InsertResult(1L, false));

        PushNotification empty = notification("");
        int availableBytes = FcmPayloadValidator.MAX_PAYLOAD_BYTES
                - serializedBytes(withLargestSystemFields(empty));
        PushNotification exact = notification("a".repeat(availableBytes));

        assertThatCode(() -> outboxService.enqueue("payload-exact", 1L, 1L, exact))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> outboxService.enqueue(
                "payload-too-large", 1L, 1L, notification("a".repeat(availableBytes) + "⚾")))
                .isInstanceOf(InvalidPushPayloadException.class)
                .hasMessageContaining("4096");

        verify(outboxWriter).insertIfAbsent(
                any(), any(), any(), any(), any(), any(), any(), any());
        verifyNoMoreInteractions(outboxWriter, metrics);
    }

    private PushNotification notification(String content) {
        return new PushNotification(
                NotificationType.RECORD_REMINDER,
                "기록 알림",
                null,
                Map.of("content", content));
    }

    private PushNotification withLargestSystemFields(PushNotification notification) {
        return notification.withSystemData(Map.of(
                "notificationId", String.valueOf(Long.MAX_VALUE),
                "schemaVersion", "1",
                "occurredAt", FIXED_NOW.toString(),
                "audienceUserId", String.valueOf(Long.MAX_VALUE)));
    }

    private int serializedBytes(PushNotification notification) throws Exception {
        return objectMapper.writeValueAsBytes(Map.of(
                "notification", Map.of("title", notification.title()),
                "data", notification.data())).length;
    }
}
