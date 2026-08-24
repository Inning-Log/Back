package com.inninglog.domain.notification.service;

public enum PushTargetOutcome {
    SUCCESS,
    INVALID,
    RETRYABLE_FAILURE,
    TERMINAL_FAILURE
}
