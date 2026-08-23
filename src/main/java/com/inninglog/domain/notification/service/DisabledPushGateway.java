package com.inninglog.domain.notification.service;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.firebase", name = "enabled", havingValue = "false", matchIfMissing = true)
public class DisabledPushGateway implements PushGateway {

    @Override
    public PushBatchResult send(PushNotification notification, List<String> pushTokens) {
        throw new PushDeliveryException("Firebase push delivery is disabled.");
    }
}
