package com.inninglog.domain.timeline.dto;

import com.inninglog.domain.timeline.model.InningHalf;
import com.inninglog.domain.timeline.model.InningRecord;
import java.time.Instant;
import java.util.UUID;

public record InningRecordResponse(
        long id, long gameId, long userGameLogId, UUID clientRecordId,
        int inning, InningHalf half, Instant recordedAt, String text,
        Integer homeScore, Integer awayScore, Instant scoreObservedAt,
        String videoStatus, Instant createdAt, Instant updatedAt
) {
    public static InningRecordResponse from(InningRecord record) {
        return new InningRecordResponse(record.id(), record.gameId(), record.userGameLogId(),
                record.clientRecordId(), record.inning(), record.half(), record.recordedAt(), record.text(),
                record.homeScore(), record.awayScore(), record.scoreObservedAt(), "UNAVAILABLE",
                record.createdAt(), record.updatedAt());
    }
}
