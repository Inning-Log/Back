package com.inninglog.domain.notification.service;

public class InvalidPushPayloadException extends RuntimeException {

    public InvalidPushPayloadException(String message) {
        super(message);
    }

    public InvalidPushPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
