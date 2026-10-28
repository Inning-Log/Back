package com.inninglog.domain.home.dto;

import com.inninglog.domain.game.repository.GameRepository.ResultCount;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Schema(description = "조회 연도 본인의 포스트시즌 직관 집계. 정규시즌과 합산하지 않음")
public record PostseasonWinRate(
        List<String> gameTypes,
        long registeredCount,
        long wins,
        long losses,
        long draws,
        long pendingCount,
        long voidCount,
        long unknownCount,
        long decidedCount,
        @Schema(description = "승/(승+패)×100, 소수점 한 자리. 승패 확정 경기가 없으면 null", nullable = true) BigDecimal winRatePercent
) {
    public static PostseasonWinRate from(List<String> types, List<ResultCount> results) {
        Map<String, Long> counts = results.stream().collect(Collectors.toMap(ResultCount::result, ResultCount::count));
        long wins = counts.getOrDefault("WIN", 0L), losses = counts.getOrDefault("LOSS", 0L);
        long draws = counts.getOrDefault("DRAW", 0L), pending = counts.getOrDefault("PENDING", 0L);
        long voids = counts.getOrDefault("VOID", 0L), unknown = counts.getOrDefault("UNKNOWN", 0L);
        long total = wins + losses + draws + pending + voids + unknown;
        if (total == 0) return null;
        long decided = wins + losses;
        BigDecimal rate = decided == 0 ? null : BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);
        return new PostseasonWinRate(types, total, wins, losses, draws, pending, voids, unknown, decided, rate);
    }
}
