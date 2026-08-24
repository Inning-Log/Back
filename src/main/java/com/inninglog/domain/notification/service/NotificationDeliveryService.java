package com.inninglog.domain.notification.service;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class NotificationDeliveryService {

    static final int FCM_MULTICAST_LIMIT = 500;

    private final PushGateway pushGateway;

    public NotificationDeliveryService(PushGateway pushGateway) {
        this.pushGateway = pushGateway;
    }

    PushBatchResult deliver(PushNotification notification, List<PushTarget> targets) {
        if (targets.isEmpty()) {
            return new PushBatchResult(List.of());
        }

        List<PushTargetResult> targetResults = new ArrayList<>();

        for (int offset = 0; offset < targets.size(); offset += FCM_MULTICAST_LIMIT) {
            List<PushTarget> batch = targets.subList(
                    offset,
                    Math.min(offset + FCM_MULTICAST_LIMIT, targets.size()));
            PushBatchResult result = pushGateway.send(notification, batch);
            targetResults.addAll(result.targetResults());
        }

        return new PushBatchResult(targetResults);
    }
}
