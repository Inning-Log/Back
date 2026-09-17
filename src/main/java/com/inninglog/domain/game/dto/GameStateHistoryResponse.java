package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.GameStateSnapshot;
import com.inninglog.domain.game.model.GameStatus;
import java.time.Instant;
import java.util.List;

public record GameStateHistoryResponse(long gameId, List<State> states) {
    public static GameStateHistoryResponse from(long gameId, List<GameStateSnapshot> snapshots) {
        return new GameStateHistoryResponse(gameId, snapshots.stream().map(State::from).toList());
    }

    public record State(
            long stateId, Instant observedAt, GameStatus status, Integer inning, String half,
            Integer homeScore, Integer awayScore, String source
    ) {
        private static State from(GameStateSnapshot snapshot) {
            return new State(snapshot.id(), snapshot.observedAt(), snapshot.status(), snapshot.currentInning(),
                    snapshot.currentHalf(), snapshot.homeScore(), snapshot.awayScore(), snapshot.resultSource());
        }
    }
}
