package com.inninglog.domain.notification.entity;

public enum NotificationOutboxStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_FAILURES
}
