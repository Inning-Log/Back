package com.inninglog.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WinRateResponse(
        @Schema(description = "집계한 시즌 연도. year 생략 시 KST 현재 연도") int seasonYear,
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
        Instant calculatedAt,
        @Schema(description = "seasonYear와 같은 연도의 포스트시즌 직관 집계. 본인의 활성 STADIUM 등록이 없으면 null. 최상위 통계는 기존 정규시즌 의미 유지", nullable = true) PostseasonWinRate postseason
) {}
