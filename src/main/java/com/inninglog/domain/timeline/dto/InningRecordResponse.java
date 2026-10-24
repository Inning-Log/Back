package com.inninglog.domain.timeline.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.timeline.model.InningHalf;
import com.inninglog.domain.timeline.model.InningRecord;
import java.time.Instant;
import java.util.UUID;

public record InningRecordResponse(
        long id,
        long gameId,
        long userGameLogId,
        UUID clientRecordId,
        int inning,
        @Schema(description = "촬영 당시 초/말. 미지정이면 null", nullable = true) InningHalf half,
        Instant recordedAt,
        String text,
        @Schema(description = "촬영 시점까지 관측된 홈 점수. 관측 이력이 없으면 null", nullable = true) Integer homeScore,
        @Schema(description = "촬영 시점까지 관측된 원정 점수. 관측 이력이 없으면 null", nullable = true) Integer awayScore,
        @Schema(description = "해당 점수의 관측 시각. 촬영 시점 이전 관측 이력이 없으면 null", nullable = true) Instant scoreObservedAt,
        @Schema(description = "UNAVAILABLE: 영상 없음, PENDING: 업로드 미확인, READY: 영상 조회 가능", allowableValues = {"UNAVAILABLE", "PENDING", "READY"}) String videoStatus,
        Instant createdAt,
        Instant updatedAt
) {
    public static InningRecordResponse from(InningRecord record) {
        return new InningRecordResponse(record.id(), record.gameId(), record.userGameLogId(),
                record.clientRecordId(), record.inning(), record.half(), record.recordedAt(), record.text(),
                record.homeScore(), record.awayScore(), record.scoreObservedAt(),
                // UNAVAILABLE: no video, PENDING: upload not confirmed yet, READY: viewable through the video API.
                record.videoStatus() == null ? "UNAVAILABLE" : record.videoStatus(),
                record.createdAt(), record.updatedAt());
    }
}
