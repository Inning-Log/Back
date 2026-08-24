package com.inninglog.domain.notification.entity;

public enum NotificationTargetStatus {
    PENDING,
    PROCESSING,
    RETRY,
    SENT,
    INVALID,
    DEAD,
    CANCELLED_OWNERSHIP
}
