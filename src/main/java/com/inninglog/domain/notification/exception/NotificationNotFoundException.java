package com.inninglog.domain.notification.exception;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException() {
        super("Notification was not found.");
    }
}
