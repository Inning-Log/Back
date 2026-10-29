package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.*;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;

public record GameResponse(
        long gameId,
        int seasonYear,
        GameType gameType,
        LocalDate date,
        @Schema(description = "더블헤더 경기 순번. 순번 정보가 없으면 null", nullable = true) Integer gameSequence,
        @Schema(description = "예정 시작 시각. 미확정이면 null", nullable = true) Instant scheduledAt,
        GameStatus status,
        Game.Team homeTeam,
        Game.Team awayTeam,
        @Schema(description = "구장 미확정 시 null", nullable = true) String stadiumName,
        Score score,
        @Schema(description = "응원 기준 팀. 본인의 활성 관람 기록이 있으면 기록 당시 cheeringTeamId를 사용하며, 없으면 현재 프로필 응원팀을 사용합니다. 해당 경기 참가 팀이 아니면 null", nullable = true) Game.Team displayTeam,
        @Schema(description = "응원 기준 팀이 없으면 null", nullable = true) Game.Team opponentTeam,
        @Schema(description = "displayTeam 기준 경기 결과. 관람 기록이 있으면 프로필 응원팀 변경 후에도 기록 당시 응원팀 기준을 유지합니다.") GameResult displayResult,
        @Schema(description = "인증 사용자 본인의 관람 등록. 미등록이면 null. 친구 타임라인에서도 본인 기준", nullable = true) Viewing myViewing,
        @Schema(description = "본인의 활성 기록 기준 영상 상태. READY: 검증된 영상 있음, NONE: 기록 없음, UNAVAILABLE: 기록은 있지만 조회 가능한 영상 없음. 관람 등록만으로 READY가 되지 않습니다.",
                allowableValues = {"READY", "NONE", "UNAVAILABLE"}) String recordingState,
        @Schema(description = "경기 상태 관측 시각. 관측 전 null", nullable = true) Instant resultObservedAt,
        @Schema(description = "본인의 활성 이닝 기록 수. 영상 업로드 완료 여부와 별개입니다.") long recordCount,
        @Schema(description = "본인의 활성 이닝 기록 존재 여부. 영상 재생 가능 여부는 recordingState를 사용합니다.") boolean hasRecords
) {
    public record Score(
            @Schema(description = "홈팀 점수. 관측 전 null이며 0점과 구분", nullable = true) Integer home,
            @Schema(description = "원정팀 점수. 관측 전 null이며 0점과 구분", nullable = true) Integer away
    ) {}
    public record Viewing(long id, ViewingType viewingType, long cheeringTeamId) {}

    public static GameResponse from(Game game, UserGameLog log, Long favoriteTeamId, GameRecordingSummary summary) {
        // A hidden viewing must never expose its old record or video availability.
        if (log == null || log.deletedAt() != null) {
            log = null;
            summary = GameRecordingSummary.EMPTY;
        }
        Long displayId = log != null ? Long.valueOf(log.cheeringTeamId()) : favoriteTeamId;
        Game.Team display = displayId == null || !game.includesTeam(displayId) ? null
                : game.homeTeam().id() == displayId ? game.homeTeam() : game.awayTeam();
        Game.Team opponent = display == null ? null : display.id() == game.homeTeam().id() ? game.awayTeam() : game.homeTeam();
        return new GameResponse(game.id(), game.seasonYear(), game.gameType(), game.gameDate(), game.gameSequence(),
                game.scheduledAt(), game.status(), game.homeTeam(), game.awayTeam(), game.stadiumName(),
                new Score(game.homeScore(), game.awayScore()), display, opponent,
                display == null ? GameResult.UNKNOWN : game.resultFor(display.id()),
                log == null ? null : new Viewing(log.id(), log.viewingType(), log.cheeringTeamId()),
                summary.recordingState(), game.resultObservedAt(), summary.recordCount(), summary.recordCount() > 0);
    }
}
