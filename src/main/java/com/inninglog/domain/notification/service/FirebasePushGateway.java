package com.inninglog.domain.notification.service;

import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.Notification;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "true")
public class FirebasePushGateway implements PushGateway {

    private final FirebaseMessaging firebaseMessaging;

    public FirebasePushGateway(FirebaseMessaging firebaseMessaging) {
        this.firebaseMessaging = firebaseMessaging;
    }

    // Flutter FCM clients currently register with a registration token. Firebase 9.10 keeps
    // token multicast for the migration period while recommending FIDs for newer clients.
    @SuppressWarnings("deprecation")
    @Override
    public PushBatchResult send(PushNotification notification, List<String> pushTokens) {
        if (pushTokens.isEmpty()) {
            return new PushBatchResult(0, 0, List.of());
        }

        Notification.Builder notificationBuilder = Notification.builder()
                .setTitle(notification.title());
        if (notification.body() != null) {
            notificationBuilder.setBody(notification.body());
        }

        MulticastMessage message = MulticastMessage.builder()
                .setNotification(notificationBuilder.build())
                .putAllData(notification.data())
                .addAllTokens(pushTokens)
                .build();

        try {
            BatchResponse response = firebaseMessaging.sendEachForMulticast(message);
            return toResult(response, pushTokens);
        } catch (FirebaseMessagingException exception) {
            throw new PushDeliveryException("Firebase Cloud Messaging request failed.", exception);
        }
    }

    private static PushBatchResult toResult(BatchResponse response, List<String> pushTokens) {
        List<String> invalidTokens = new ArrayList<>();
        List<SendResponse> responses = response.getResponses();
        for (int index = 0; index < responses.size(); index++) {
            FirebaseMessagingException exception = responses.get(index).getException();
            if (exception != null && exception.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED) {
                invalidTokens.add(pushTokens.get(index));
            }
        }
        return new PushBatchResult(response.getSuccessCount(), response.getFailureCount(), invalidTokens);
    }
}
