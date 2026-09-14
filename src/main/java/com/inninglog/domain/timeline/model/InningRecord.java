package com.inninglog.domain.timeline.model;

import java.time.Instant;
import java.util.UUID;

public record InningRecord(
        long id, long userGameLogId, long userId, long gameId, UUID clientRecordId,
        String requestFingerprint, int inning, InningHalf half, String text, Instant recordedAt,
        Integer homeScore, Integer awayScore, Instant scoreObservedAt,
        Instant createdAt, Instant updatedAt, Instant deletedAt
) {}
