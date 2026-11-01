package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.GameStateSnapshot;
import com.inninglog.domain.game.model.GameStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Compact per-game evidence for reviewing scores on multiple recorded moments. */
public record GameScoreHistoryResponse(
        long gameId,
        @Schema(description = "OBSERVED: 원본 점수를 수집한 시각이며 실제 득점 시각이 아님") String timeBasis,
        boolean actualEventTimeAvailable,
        boolean automaticCorrectionAvailable,
        List<ScoreChange> changes
) {
    public static GameScoreHistoryResponse from(long gameId, List<GameStateSnapshot> states) {
        var changes = new ArrayList<ScoreChange>();
        GameStateSnapshot previous = null;
        boolean observedScore = false;
        for (var current : states) {
            if (!current.hasScore() || !List.of(GameStatus.LIVE, GameStatus.SUSPENDED, GameStatus.FINISHED).contains(current.status())) {
                previous = null;
                continue;
            }
            if (previous == null || !sameScore(previous, current)) {
                String type = previous == null ? (observedScore ? "BASELINE_AFTER_GAP" : "INITIAL_OBSERVATION")
                        : current.homeScore() < previous.homeScore() || current.awayScore() < previous.awayScore()
                        ? "SCORE_CORRECTION" : "SCORE_INCREASE";
                Instant beforeAt = null;
                var evidence = current.previousScoreObservation();
                if (previous != null && evidence != null && "SCOREBOARD".equals(current.resultSource())
                        && evidence.homeScore() == previous.homeScore() && evidence.awayScore() == previous.awayScore()
                        && !evidence.observedAt().isBefore(previous.observedAt())
                        && evidence.observedAt().isBefore(current.observedAt())) beforeAt = evidence.observedAt();
                changes.add(new ScoreChange(current.id(), type, current.currentInning(), current.currentHalf(),
                        previous == null ? null : ObservedScore.from(previous), ObservedScore.from(current), beforeAt,
                        current.observedAt(), beforeAt == null ? null : Duration.between(beforeAt, current.observedAt()).toMillis(),
                        null, current.resultSource()));
            }
            previous = current;
            observedScore = true;
        }
        return new GameScoreHistoryResponse(gameId, "OBSERVED", false, false, List.copyOf(changes));
    }

    private static boolean sameScore(GameStateSnapshot a, GameStateSnapshot b) {
        return Objects.equals(a.homeScore(), b.homeScore()) && Objects.equals(a.awayScore(), b.awayScore());
    }

    public record ObservedScore(int homeScore, int awayScore) {
        private static ObservedScore from(GameStateSnapshot state) { return new ObservedScore(state.homeScore(), state.awayScore()); }
    }

    public record ScoreChange(
            long stateId,
            @Schema(description = "INITIAL_OBSERVATION, BASELINE_AFTER_GAP, SCORE_INCREASE, SCORE_CORRECTION") String type,
            @Schema(nullable = true) Integer inning,
            @Schema(nullable = true) String half,
            @Schema(nullable = true, description = "이전 점수. 최초 관측 또는 관측 단절 후에는 null") ObservedScore before,
            ObservedScore after,
            @Schema(nullable = true, description = "변경 전 점수를 마지막으로 확인한 스코어보드 관측 시각. 근거 없으면 null") Instant previousScoreObservedAt,
            @Schema(description = "변경 후 점수를 확인한 관측 시각. 실제 득점 시각이 아님") Instant observedAt,
            @Schema(nullable = true, description = "앞뒤 스코어보드 관측 간격(ms). 원본 자체의 지연은 포함하지 않음") Long observationGapMillis,
            @Schema(nullable = true, description = "실제 득점 시각. 현재 소스에서 확인할 수 없어 null") Instant occurredAt,
            String source
    ) {}
}
