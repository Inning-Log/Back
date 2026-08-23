package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.repository.UserPushTokenRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryService {

    static final int FCM_MULTICAST_LIMIT = 500;

    private final UserPushTokenRepository pushTokenRepository;
    private final PushGateway pushGateway;
    private final PushTokenCleanupService pushTokenCleanupService;

    public NotificationDeliveryService(
            UserPushTokenRepository pushTokenRepository,
            PushGateway pushGateway,
            PushTokenCleanupService pushTokenCleanupService
    ) {
        this.pushTokenRepository = pushTokenRepository;
        this.pushGateway = pushGateway;
        this.pushTokenCleanupService = pushTokenCleanupService;
    }

    public NotificationDeliveryResult sendToUser(Long userId, PushNotification notification) {
        List<String> pushTokens = pushTokenRepository.findEnabledPushTokensByUserId(userId);
        if (pushTokens.isEmpty()) {
            return NotificationDeliveryResult.noTargets();
        }

        int successes = 0;
        int failures = 0;
        List<String> invalidTokens = new ArrayList<>();

        for (int offset = 0; offset < pushTokens.size(); offset += FCM_MULTICAST_LIMIT) {
            List<String> batch = pushTokens.subList(
                    offset,
                    Math.min(offset + FCM_MULTICAST_LIMIT, pushTokens.size()));
            PushBatchResult result = pushGateway.send(notification, batch);
            successes += result.successCount();
            failures += result.failureCount();
            invalidTokens.addAll(result.invalidPushTokens());
        }

        pushTokenCleanupService.disableInvalidTokens(invalidTokens);
        return new NotificationDeliveryResult(pushTokens.size(), successes, failures);
    }
}
