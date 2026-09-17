package com.inninglog.domain.game.model;

import java.time.Instant;

/** One durable, effective game state. Consecutive rows always differ meaningfully. */
public record GameStateSnapshot(
        long id, long gameId, GameStatus status, Integer currentInning, String currentHalf,
        Integer homeScore, Integer awayScore, Instant observedAt, String resultSource
) {
    public boolean hasScore() {
        return homeScore != null && awayScore != null;
    }
}
