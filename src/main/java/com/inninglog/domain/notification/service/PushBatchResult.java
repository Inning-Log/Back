package com.inninglog.domain.notification.service;

import java.util.List;

public record PushBatchResult(List<PushTargetResult> targetResults) {

    public PushBatchResult {
        targetResults = List.copyOf(targetResults);
    }

    public int successCount() {
        return count(PushTargetOutcome.SUCCESS);
    }

    public int failureCount() {
        return targetResults.size() - successCount();
    }

    public int count(PushTargetOutcome outcome) {
        return (int) targetResults.stream()
                .filter(result -> result.outcome() == outcome)
                .count();
    }
}
