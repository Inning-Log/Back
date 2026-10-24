package com.inninglog.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WinRateResponse(
        int seasonYear,
        List<String> gameTypes,
        String viewingType,
        long registeredCount,
        long wins,
        long losses,
        long draws,
        long pendingCount,
        long voidCount,
        long unknownCount,
        long unclassifiedCount,
        long decidedCount,
        @Schema(description = "승패가 확정된 직관 경기가 없으면 null. 무승부는 승률 분모에서 제외", nullable = true) BigDecimal winRatePercent,
        Instant calculatedAt
) {}
