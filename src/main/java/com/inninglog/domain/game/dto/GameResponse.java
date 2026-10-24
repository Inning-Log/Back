package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record GameResponse(
        long gameId, int seasonYear, GameType gameType, LocalDate date, Integer gameSequence,
        Instant scheduledAt, GameStatus status, Game.Team homeTeam, Game.Team awayTeam,
        String stadiumName, Score score, Game.Team displayTeam, Game.Team opponentTeam,
        GameResult displayResult, Viewing myViewing,
        @Schema(description = "본인의 활성 기록 기준 영상 상태. READY: 검증된 영상 있음, NONE: 기록 없음, UNAVAILABLE: 기록은 있지만 조회 가능한 영상 없음. 관람 등록만으로 READY가 되지 않습니다.",
                allowableValues = {"READY", "NONE", "UNAVAILABLE"}) String recordingState,
        Instant resultObservedAt,
        @Schema(description = "본인의 활성 이닝 기록 수. 영상 업로드 완료 여부와 별개입니다.") long recordCount,
        @Schema(description = "본인의 활성 이닝 기록 존재 여부. 영상 재생 가능 여부는 recordingState를 사용합니다.") boolean hasRecords
) {
    public record Score(Integer home, Integer away) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}

    public static GameResponse from(Game game, UserGameLog log, Long favoriteTeamId, GameRecordingSummary summary) {
        // A hidden viewing must never expose its old record or video availability.
        if (log == null || log.deletedAt() != null) {
            log = null;
            summary = GameRecordingSummary.EMPTY;
        }
        Long displayId = favoriteTeamId != null && game.includesTeam(favoriteTeamId)
                ? favoriteTeamId : log == null ? null : log.cheeringTeamId();
        Game.Team display = displayId == null ? null : game.homeTeam().id() == displayId ? game.homeTeam() : game.awayTeam();
        Game.Team opponent = display == null ? null : display.id() == game.homeTeam().id() ? game.awayTeam() : game.homeTeam();
        return new GameResponse(game.id(), game.seasonYear(), game.gameType(), game.gameDate(), game.gameSequence(),
                game.scheduledAt(), game.status(), game.homeTeam(), game.awayTeam(), game.stadiumName(),
                new Score(game.homeScore(), game.awayScore()), display, opponent,
                display == null ? GameResult.UNKNOWN : game.resultFor(display.id()),
                log == null ? null : new Viewing(log.id(), log.viewingType(), log.cheeringTeamId()),
                summary.recordingState(), game.resultObservedAt(), summary.recordCount(), summary.recordCount() > 0);
    }
}
