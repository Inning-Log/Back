package com.inninglog.domain.notification.service;

public record NotificationDeliveryResult(int targetCount, int successCount, int failureCount) {

    public static NotificationDeliveryResult noTargets() {
        return new NotificationDeliveryResult(0, 0, 0);
    }
}
