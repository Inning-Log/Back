package com.inninglog.domain.game.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.game.model.GameStateSnapshot;
import com.inninglog.domain.game.model.GameStatus;
import java.time.Instant;
import java.util.List;

public record GameStateHistoryResponse(long gameId, List<State> states) {
    public static GameStateHistoryResponse from(long gameId, List<GameStateSnapshot> snapshots) {
        return new GameStateHistoryResponse(gameId, snapshots.stream().map(State::from).toList());
    }

    public record State(
            long stateId,
            Instant observedAt,
            GameStatus status,
            @Schema(description = "현재 이닝. 확인되지 않으면 null", nullable = true) Integer inning,
            @Schema(description = "초/말 정보. 확인되지 않으면 null", nullable = true) String half,
            @Schema(description = "홈팀 점수. 확인되지 않으면 null", nullable = true) Integer homeScore,
            @Schema(description = "원정팀 점수. 확인되지 않으면 null", nullable = true) Integer awayScore,
            String source
    ) {
        private static State from(GameStateSnapshot snapshot) {
            return new State(snapshot.id(), snapshot.observedAt(), snapshot.status(), snapshot.currentInning(),
                    snapshot.currentHalf(), snapshot.homeScore(), snapshot.awayScore(), snapshot.resultSource());
        }
    }
}
