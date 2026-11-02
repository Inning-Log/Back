package com.inninglog.domain.gamevideo.model;

import java.math.BigDecimal;
import java.time.Instant;

public record GameVideo(
        long id, long userId, long gameId, GameVideoMode mode, GameVideoStatus status, String objectKey,
        int segmentCount, BigDecimal durationSeconds, String failureReason,
        Instant requestedAt, Instant mergeRequestedAt, Instant completedAt, Instant deletedAt
) {
    public boolean inProgress() {
        return status == GameVideoStatus.RENDERING || status == GameVideoStatus.MERGING;
    }
}
