package com.inninglog.domain.notification.service;

import java.util.List;

public record PushBatchResult(int successCount, int failureCount, List<String> invalidPushTokens) {

    public PushBatchResult {
        invalidPushTokens = List.copyOf(invalidPushTokens);
    }
}
