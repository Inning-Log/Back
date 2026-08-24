package com.inninglog.domain.notification.exception;

public class InvalidNotificationSettingsException extends RuntimeException {

    public InvalidNotificationSettingsException() {
        super("At least one notification setting must be provided.");
    }
}
