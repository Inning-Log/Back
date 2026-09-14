package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.*;
import java.time.Instant;
import java.time.LocalDate;

public record GameResponse(
        long gameId, int seasonYear, GameType gameType, LocalDate date, Integer gameSequence,
        Instant scheduledAt, GameStatus status, Game.Team homeTeam, Game.Team awayTeam,
        String stadiumName, Score score, Game.Team displayTeam, Game.Team opponentTeam,
        GameResult displayResult, Viewing myViewing, String recordingState, Instant resultObservedAt,
        long recordCount, boolean hasRecords
) {
    public record Score(Integer home, Integer away) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}

    public static GameResponse from(Game game, UserGameLog log, Long favoriteTeamId) {
        return from(game, log, favoriteTeamId, 0);
    }

    public static GameResponse from(Game game, UserGameLog log, Long favoriteTeamId, long recordCount) {
        Long displayId = favoriteTeamId != null && game.includesTeam(favoriteTeamId)
                ? favoriteTeamId : log == null ? null : log.cheeringTeamId();
        Game.Team display = displayId == null ? null : game.homeTeam().id() == displayId ? game.homeTeam() : game.awayTeam();
        Game.Team opponent = display == null ? null : display.id() == game.homeTeam().id() ? game.awayTeam() : game.homeTeam();
        return new GameResponse(game.id(), game.seasonYear(), game.gameType(), game.gameDate(), game.gameSequence(),
                game.scheduledAt(), game.status(), game.homeTeam(), game.awayTeam(), game.stadiumName(),
                new Score(game.homeScore(), game.awayScore()), display, opponent,
                display == null ? GameResult.UNKNOWN : game.resultFor(display.id()),
                log == null ? null : new Viewing(log.id(), log.viewingType(), log.cheeringTeamId()),
                log == null ? "NONE" : "UNAVAILABLE", game.resultObservedAt(), recordCount, recordCount > 0);
    }
}
