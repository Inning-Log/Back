package com.inninglog.domain.game.model;

import java.time.Instant;
import java.time.LocalDate;

/** Immutable database projection; writes are owned by the transactional importer. */
public record Game(
        long id, int seasonYear, GameType gameType, LocalDate gameDate, Integer gameSequence,
        Instant scheduledAt, Instant startedAt, Instant endedAt,
        Team homeTeam, Team awayTeam, String stadiumName,
        Integer homeScore, Integer awayScore, GameStatus status,
        Integer currentInning, String currentHalf, Instant scheduleObservedAt,
        Instant resultObservedAt, String resultSource
) {
    public record Team(long id, String teamCode, String name, String shortName, String logoUrl) {}

    public boolean includesTeam(long teamId) {
        return homeTeam.id() == teamId || awayTeam.id() == teamId;
    }

    public GameResult resultFor(long teamId) {
        if (!includesTeam(teamId)) return GameResult.UNKNOWN;
        return switch (status) {
            case CANCELED, POSTPONED -> GameResult.VOID;
            case SCHEDULED, LIVE, DELAYED, SUSPENDED -> GameResult.PENDING;
            case UNKNOWN -> GameResult.UNKNOWN;
            case FINISHED -> {
                if (homeScore == null || awayScore == null) yield GameResult.UNKNOWN;
                int comparison = Integer.compare(homeScore, awayScore);
                if (homeTeam.id() != teamId) comparison = -comparison;
                yield comparison > 0 ? GameResult.WIN : comparison < 0 ? GameResult.LOSS : GameResult.DRAW;
            }
        };
    }
}
