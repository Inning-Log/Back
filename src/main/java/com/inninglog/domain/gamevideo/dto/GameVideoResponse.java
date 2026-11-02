package com.inninglog.domain.gamevideo.dto;

import com.inninglog.domain.gamevideo.model.GameVideoMode;
import com.inninglog.domain.gamevideo.model.GameVideoStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record GameVideoResponse(
        long gameVideoId, long gameId, GameVideoStatus status, GameVideoMode mode,
        @Schema(description = "카드 순서. 첫 번째가 요청한 사용자") List<Long> memberUserIds,
        int segmentCount,
        @Schema(description = "완성된 이닝 구간 수. 진행률 표시용") int readySegmentCount,
        @Schema(nullable = true) BigDecimal durationSeconds,
        @Schema(description = "FAILED일 때 원인 코드", nullable = true) String failureReason,
        @Schema(description = "READY일 때만 값이 있습니다. 만료되면 이 API로 다시 받습니다.", nullable = true) String downloadUrl,
        @Schema(nullable = true) Instant expiresAt,
        Instant requestedAt,
        @Schema(nullable = true) Instant completedAt
) {}
