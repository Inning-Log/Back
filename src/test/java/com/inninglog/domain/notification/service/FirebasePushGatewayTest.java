package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void onlyUnregisteredIsClassifiedAsAnInvalidRegistration() throws Exception {
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
                new PushTarget(11L, 21L, "token-a"),
                new PushTarget(12L, 22L, "token-b"));
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

    private List<PushTarget> targets() {
        return List.of(new PushTarget(1L, 2L, "token"));
    }
}
