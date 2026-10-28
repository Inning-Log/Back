package com.inninglog.domain.home.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "조회 연도의 정규시즌·포스트시즌 합산 직관 통계. 기록 당시 응원팀으로 승패 계산")
public record WinRateResponse(
        @Schema(description = "집계한 시즌 연도. year 생략 시 KST 현재 연도") int seasonYear,
        @Schema(description = "전체 통계에 포함한 경기 종류: REGULAR, WILDCARD, SEMI_PLAYOFF, PLAYOFF, KOREAN_SERIES") List<String> gameTypes,
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
        @Schema(description = "seasonYear와 같은 연도의 포스트시즌 상세 집계. 최상위 합산 통계에 이미 포함되므로 다시 더하지 않음. 본인의 활성 STADIUM 등록이 없으면 null", nullable = true) PostseasonWinRate postseason
) {}
