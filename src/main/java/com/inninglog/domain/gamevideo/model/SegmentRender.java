package com.inninglog.domain.gamevideo.model;

import java.math.BigDecimal;
import java.time.Instant;

public record SegmentRender(
        long id, String renderKey, String objectKey, SegmentStatus status, BigDecimal durationSeconds,
        String failureReason, int attempts, Instant requestedAt, Instant completedAt
) {}
