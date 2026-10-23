package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.SendResponse;
import com.inninglog.domain.notification.entity.NotificationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import com.google.firebase.messaging.MulticastMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FirebasePushGatewayTest {

    @Mock
    private FirebaseMessaging firebaseMessaging;

    @Mock
    private FirebaseMessagingException messagingException;

    @Mock
    private BatchResponse batchResponse;

    @Mock
    private SendResponse sendResponse;

    private FirebasePushGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new FirebasePushGateway(
                firebaseMessaging,
                new FcmPayloadValidator(new ObjectMapper()));
    }

    @Test
    void platformUnavailableFallbackIsRetryableWhenMessagingCodeIsMissing() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenThrow(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(null);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.UNAVAILABLE);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void responseCountMismatchIsRetriedInsteadOfDropped() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of());

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("UNKNOWN");
    }

    @Test
    void perTargetInvalidArgumentIsTerminalButDoesNotDisableTheRegistration() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResponse));
        when(sendResponse.getException()).thenReturn(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INVALID_ARGUMENT);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.INVALID_ARGUMENT);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.TERMINAL_FAILURE);
        assertThat(result.errorCode()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void unregisteredIsClassifiedAsAnInvalidRegistration() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResponse));
        when(sendResponse.getException()).thenReturn(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.NOT_FOUND);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.INVALID);
        assertThat(result.errorCode()).isEqualTo("UNREGISTERED");
    }

    @Test
    void fidNotFoundIsClassifiedAsAnInvalidRegistration() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResponse));
        when(sendResponse.getException()).thenReturn(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(null);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.NOT_FOUND);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.INVALID);
        assertThat(result.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    void senderIdMismatchIsTerminalWithoutBeingClassifiedAsAnInvalidToken() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResponse));
        when(sendResponse.getException()).thenReturn(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.SENDER_ID_MISMATCH);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.PERMISSION_DENIED);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.TERMINAL_FAILURE);
        assertThat(result.errorCode()).isEqualTo("SENDER_ID_MISMATCH");
    }

    @Test
    void resourceExhaustedPlatformFailureIsRetryable() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenThrow(messagingException);
        when(messagingException.getMessagingErrorCode()).thenReturn(null);
        when(messagingException.getErrorCode()).thenReturn(ErrorCode.RESOURCE_EXHAUSTED);

        PushTargetResult result = gateway.send(notification(), targets()).targetResults().getFirst();

        assertThat(result.outcome()).isEqualTo(PushTargetOutcome.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("RESOURCE_EXHAUSTED");
    }

    @Test
    void mixedBatchResultsStayAlignedWithTheirOriginalTargets() throws Exception {
        SendResponse successResponse = mock(SendResponse.class);
        SendResponse invalidResponse = mock(SendResponse.class);
        FirebaseMessagingException invalidException = mock(FirebaseMessagingException.class);
        List<PushTarget> targets = List.of(
                new PushTarget(11L, 21L, "fid-a", 1L),
                new PushTarget(12L, 22L, "fid-b", 1L));
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(successResponse, invalidResponse));
        when(invalidResponse.getException()).thenReturn(invalidException);
        when(invalidException.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        when(invalidException.getErrorCode()).thenReturn(ErrorCode.NOT_FOUND);

        List<PushTargetResult> results = gateway.send(notification(), targets).targetResults();

        assertThat(results).extracting(PushTargetResult::deliveryTargetId)
                .containsExactly(11L, 12L);
        assertThat(results).extracting(PushTargetResult::outcome)
                .containsExactly(PushTargetOutcome.SUCCESS, PushTargetOutcome.INVALID);
    }

    private PushNotification notification() {
        return new PushNotification(
                NotificationType.FRIEND_REQUEST,
                "친구 신청이 도착했어요",
                null,
                Map.of("requestId", "10"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void webPushUsesDataOnlySoTheClientCanCheckAudienceBeforeDisplaying() throws Exception {
        when(firebaseMessaging.sendEachForMulticast(any())).thenReturn(batchResponse);
        when(batchResponse.getResponses()).thenReturn(List.of(sendResponse));
        String link = "https://inning-log.vercel.app/home/notifications";
        var notification = new PushNotification(NotificationType.FRIEND_REQUEST, "서버 제목", "서버 본문",
                Map.of("title", "덮어쓰면 안 되는 입력", "notificationId", "77", "audienceUserId", "5", "link", link))
                .withExpiry(java.time.Instant.now().plusSeconds(600));
        gateway.send(notification, targets());
        var sent = ArgumentCaptor.forClass(MulticastMessage.class);
        verify(firebaseMessaging).sendEachForMulticast(sent.capture());
        assertThat(ReflectionTestUtils.getField(sent.getValue(), "notification")).isNull();
        Map<String, String> data = (Map<String, String>) ReflectionTestUtils.getField(sent.getValue(), "data");
        assertThat(data).containsEntry("title", "서버 제목").containsEntry("body", "서버 본문")
                .containsEntry("notificationId", "77").containsEntry("audienceUserId", "5").containsEntry("link", link);
        Object webpush = ReflectionTestUtils.getField(sent.getValue(), "webpushConfig");
        assertThat(ReflectionTestUtils.getField(webpush, "notification")).isNull();
        assertThat(ReflectionTestUtils.getField(webpush, "fcmOptions")).isNull();
        Map<String, String> headers = (Map<String, String>) ReflectionTestUtils.getField(webpush, "headers");
        assertThat(Long.parseLong(headers.get("TTL"))).isBetween(1L, 600L);
    }

    private List<PushTarget> targets() {
        return List.of(new PushTarget(1L, 2L, "fid", 1L));
    }
}
