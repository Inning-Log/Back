package com.inninglog.domain.game.service;

import com.inninglog.domain.game.dto.*;
import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.game.model.*;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.user.entity.User;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GameService {
    private final GameRepository games;
    private final GameUserAccess access;
    private final Clock clock;
    public GameService(GameRepository games, GameUserAccess access, Clock clock) {
        this.games = games; this.access = access; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public GameListResponse forDate(String subject, LocalDate date) {
        User user = access.require(subject, false);
        if (date.getYear() < 1900 || date.getYear() > 9998)
            throw new IllegalArgumentException("경기 날짜 범위를 확인해 주세요.");
        Map<Long, UserGameLog> logs = games.logsForRange(user.getId(), date, date.plusDays(1)).stream()
                .collect(Collectors.toMap(UserGameLog::gameId, Function.identity()));
        var daily = games.syncState("DAY:" + date);
        var monthly = games.syncState("MONTH:" + YearMonth.from(date));
        var state = "NOT_IMPORTED".equals(daily.state()) ? monthly : daily;
        return new GameListResponse(date, today(), state, games.forDate(date).stream()
                .map(game -> GameResponse.from(game, logs.get(game.id()), user.getFavoriteTeam().getId())).toList());
    }

    @Transactional
    public Registration create(String subject, CreateUserGameLogRequest request) {
        User user = access.require(subject, true);
        UserGameLog existing = games.logForGame(user.getId(), request.gameId()).orElse(null);
        // A retry remains idempotent after midnight or a subsequent cancellation.
        if (existing != null && existing.deletedAt() == null) {
            if (existing.cheeringTeamId() != request.cheeringTeamId() || existing.viewingType() != request.viewingType())
                throw GameException.conflict("VIEWING_ALREADY_EXISTS", "이미 등록된 경기입니다. 관람 방식 정정 API를 사용해 주세요.");
            return new Registration(response(existing), false);
        }
        games.lockGame(request.gameId());
        Game game = requireGame(request.gameId());
        if (!game.gameDate().equals(today()))
            throw GameException.conflict("RECORDING_DATE_NOT_TODAY", "관람 등록은 한국 시간 기준 당일 경기만 가능합니다.");
        if (game.status() == GameStatus.CANCELED || game.status() == GameStatus.POSTPONED)
            throw GameException.conflict("GAME_NOT_RECORDABLE", "취소되거나 연기된 경기입니다.");
        if (!game.includesTeam(request.cheeringTeamId()))
            throw new IllegalArgumentException("응원팀은 해당 경기의 홈팀 또는 원정팀이어야 합니다.");
        boolean created = existing == null;
        long id;
        if (created) id = games.createLog(user.getId(), game.id(), request.cheeringTeamId(), request.viewingType(), clock.instant());
        else {
            id = existing.id();
            games.restoreLog(id, request.cheeringTeamId(), request.viewingType(), clock.instant());
        }
        return new Registration(response(games.logById(user.getId(), id).orElseThrow()), created);
    }

    @Transactional(readOnly = true)
    public UserGameLogResponse get(String subject, long id) {
        User user = access.require(subject, false);
        return response(requireActiveLog(user.getId(), id));
    }

    @Transactional
    public UserGameLogResponse update(String subject, long id, ViewingType type) {
        User user = access.require(subject, true);
        UserGameLog log = requireActiveLog(user.getId(), id);
        games.updateLog(log.id(), type, clock.instant());
        return response(games.logById(user.getId(), id).orElseThrow());
    }

    @Transactional
    public void delete(String subject, long id) {
        User user = access.require(subject, true);
        UserGameLog log = games.logById(user.getId(), id).orElseThrow(() -> GameException.notFound("VIEWING_NOT_FOUND"));
        games.deleteLog(log.id(), clock.instant());
    }

    private UserGameLog requireActiveLog(long userId, long id) {
        return games.logById(userId, id).filter(log -> log.deletedAt() == null)
                .orElseThrow(() -> GameException.notFound("VIEWING_NOT_FOUND"));
    }
    private Game requireGame(long id) { return games.find(id).orElseThrow(() -> GameException.notFound("GAME_NOT_FOUND")); }
    private UserGameLogResponse response(UserGameLog log) { return UserGameLogResponse.from(log, requireGame(log.gameId())); }
    private LocalDate today() { return LocalDate.now(clock.withZone(GameUserAccess.KST)); }
    public record Registration(UserGameLogResponse response, boolean created) {}
}
