package com.inninglog.domain.notification.service;

public record PushTarget(Long deliveryTargetId, Long registrationId, String pushToken) {
}
