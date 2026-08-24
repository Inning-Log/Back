package com.inninglog.domain.notification.service;

import com.google.firebase.ErrorCode;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import com.google.firebase.messaging.WebpushConfig;
import com.google.firebase.messaging.WebpushFcmOptions;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
public class FirebasePushGateway implements PushGateway {

    private final FirebaseMessaging firebaseMessaging;
    private final FcmPayloadValidator payloadValidator;

    public FirebasePushGateway(FirebaseMessaging firebaseMessaging, FcmPayloadValidator payloadValidator) {
        this.firebaseMessaging = firebaseMessaging;
        this.payloadValidator = payloadValidator;
    }

    private static final Set<MessagingErrorCode> RETRYABLE_ERROR_CODES = EnumSet.of(
            MessagingErrorCode.INTERNAL,
            MessagingErrorCode.QUOTA_EXCEEDED,
            MessagingErrorCode.UNAVAILABLE
    );
    private static final Set<ErrorCode> RETRYABLE_PLATFORM_ERROR_CODES = EnumSet.of(
            ErrorCode.ABORTED,
            ErrorCode.CANCELLED,
            ErrorCode.DEADLINE_EXCEEDED,
            ErrorCode.INTERNAL,
            ErrorCode.RESOURCE_EXHAUSTED,
            ErrorCode.UNAVAILABLE,
            ErrorCode.UNKNOWN
    );

    @Override
    public PushBatchResult send(PushNotification notification, List<PushTarget> targets) {
        if (targets.isEmpty()) {
            return new PushBatchResult(List.of());
        }

        payloadValidator.validate(notification);

        Notification.Builder notificationBuilder = Notification.builder()
                .setTitle(notification.title());
        if (notification.body() != null) {
            notificationBuilder.setBody(notification.body());
        }

        MulticastMessage.Builder messageBuilder = MulticastMessage.builder()
                .setNotification(notificationBuilder.build())
                .putAllData(notification.data())
                .addAllFids(targets.stream().map(PushTarget::installationId).toList());
        String link = notification.data().get("link");
        if (link != null) {
            messageBuilder.setWebpushConfig(WebpushConfig.builder()
                    .setFcmOptions(WebpushFcmOptions.withLink(link))
                    .build());
        }
        MulticastMessage message = messageBuilder.build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            return toResult(response, targets);
        } catch (FirebaseMessagingException exception) {
            return wholeBatchFailure(
                    targets,
                    exception.getMessagingErrorCode(),
                    exception.getErrorCode());
        }
    }

    private static PushBatchResult toResult(BatchResponse response, List<PushTarget> targets) {
        List<SendResponse> responses = response.getResponses();
        if (responses.size() != targets.size()) {
            return wholeBatchFailure(targets, null, null);
        }

        List<PushTargetResult> results = new ArrayList<>(responses.size());
        for (int index = 0; index < responses.size(); index++) {
            PushTarget target = targets.get(index);
            FirebaseMessagingException exception = responses.get(index).getException();
            if (exception == null) {
                results.add(PushTargetResult.success(target));
                continue;
            }

            MessagingErrorCode errorCode = exception.getMessagingErrorCode();
            ErrorCode platformErrorCode = exception.getErrorCode();
            results.add(PushTargetResult.failure(
                    target,
                    classifyTarget(errorCode, platformErrorCode),
                    errorName(errorCode, platformErrorCode)));
        }
        return new PushBatchResult(results);
    }

    private static PushBatchResult wholeBatchFailure(
            List<PushTarget> targets,
            MessagingErrorCode errorCode,
            ErrorCode platformErrorCode
    ) {
        PushTargetOutcome outcome = classifyBatch(errorCode, platformErrorCode);
        String errorName = errorName(errorCode, platformErrorCode);
        return new PushBatchResult(targets.stream()
                .map(target -> PushTargetResult.failure(target, outcome, errorName))
                .toList());
    }

    private static PushTargetOutcome classifyTarget(
            MessagingErrorCode errorCode,
            ErrorCode platformErrorCode
    ) {
        if (errorCode == MessagingErrorCode.UNREGISTERED
                || (errorCode == null && platformErrorCode == ErrorCode.NOT_FOUND)) {
            return PushTargetOutcome.INVALID;
        }
        return classifyBatch(errorCode, platformErrorCode);
    }

    private static PushTargetOutcome classifyBatch(
            MessagingErrorCode errorCode,
            ErrorCode platformErrorCode
    ) {
        if ((errorCode != null && RETRYABLE_ERROR_CODES.contains(errorCode))
                || (platformErrorCode != null && RETRYABLE_PLATFORM_ERROR_CODES.contains(platformErrorCode))
                || (errorCode == null && platformErrorCode == null)) {
            return PushTargetOutcome.RETRYABLE_FAILURE;
        }
        return PushTargetOutcome.TERMINAL_FAILURE;
    }

    private static String errorName(MessagingErrorCode errorCode, ErrorCode platformErrorCode) {
        if (errorCode != null) {
            return errorCode.name();
        }
        return platformErrorCode == null ? "UNKNOWN" : platformErrorCode.name();
    }
}
