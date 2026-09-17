package com.inninglog.domain.timeline.service;

import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.model.GameStatus;
import com.inninglog.domain.game.model.UserGameLog;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.timeline.dto.*;
import com.inninglog.domain.timeline.model.InningRecord;
import com.inninglog.domain.timeline.repository.TimelineRepository;
import com.inninglog.domain.timeline.repository.TimelineRepository.Position;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class TimelineService {
    private final TimelineRepository records;
    private final GameRepository games;
    private final GameUserAccess access;
    private final Clock clock;
    private final ObjectMapper mapper;

    public TimelineService(TimelineRepository records, GameRepository games, GameUserAccess access,
                           Clock clock, ObjectMapper mapper) {
        this.records = records;
        this.games = games;
        this.access = access;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TimelineResponse timeline(String subject, Long requestedOwnerId, long gameId, String cursor, int limit) {
        var viewer = access.require(subject, false);
        long ownerId = requestedOwnerId == null ? viewer.getId() : requestedOwnerId;
        var owner = visibleOwner(viewer.getId(), ownerId);
        Game game = requireGame(gameId);
        UserGameLog log = activeLog(ownerId, gameId).orElse(null);
        Position after = decodeCursor(cursor);
        if (after != null && (log == null || after.logId() != log.id()))
            throw new IllegalArgumentException("다른 타임라인의 커서입니다.");
        List<InningRecord> page = log == null ? List.of() : records.page(log.id(), after, limit + 1);
        boolean hasMore = page.size() > limit;
        if (hasMore) page = page.subList(0, limit);
        boolean own = viewer.getId() == ownerId;
        boolean writableGame = recordableToday(game);
        var viewing = log == null ? null : new TimelineResponse.Viewing(log.id(), log.viewingType(), log.cheeringTeamId());
        // game.myViewing always means the authenticated viewer, even on a friend's timeline.
        var myLog = own ? log : activeLog(viewer.getId(), gameId).orElse(null);
        long count = log == null ? 0 : records.count(log.id());
        long myCount = own ? count : myLog == null ? 0 : records.count(myLog.id());
        return new TimelineResponse(today(), "Asia/Seoul", owner,
                GameResponse.from(game, myLog, viewer.getFavoriteTeam().getId(), myCount), viewing,
                game.currentInning(), game.currentHalf(), own && writableGame && log != null,
                own && writableGame && log == null, count,
                page.stream().map(InningRecordResponse::from).toList(),
                hasMore ? encodeCursor(page.getLast()) : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TimelineProfilesResponse profiles(String subject, long afterUserId, int limit) {
        var viewer = access.require(subject, false);
        var me = visibleOwner(viewer.getId(), viewer.getId());
        var friends = records.friends(viewer.getId(), afterUserId, limit + 1);
        boolean hasMore = friends.size() > limit;
        if (hasMore) friends = friends.subList(0, limit);
        List<Long> ids = new ArrayList<>();
        ids.add(viewer.getId());
        friends.forEach(friend -> ids.add(friend.userId()));
        var byUser = records.profileGames(ids, today());
        return new TimelineProfilesResponse(today(), profile(me, byUser.getOrDefault(me.userId(), List.of())),
                friends.stream().map(friend -> profile(friend, byUser.getOrDefault(friend.userId(), List.of()))).toList(),
                hasMore ? friends.getLast().userId() : null);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InningRecordResponse get(String subject, long recordId) {
        var viewer = access.require(subject, false);
        var record = requireRecord(recordId, false);
        visibleOwner(viewer.getId(), record.userId());
        return InningRecordResponse.from(record);
    }

    @Transactional
    public Registration create(String subject, CreateInningRecordRequest request) {
        var user = access.require(subject, true);
        var log = activeLog(user.getId(), request.gameId()).orElseThrow(() ->
                GameException.conflict("VIEWING_REQUIRED", "해당 경기의 직관·집관을 먼저 등록해 주세요."));
        Instant captured = request.recordedAt().truncatedTo(ChronoUnit.MICROS);
        String fingerprint = fingerprint(request, captured);
        var existing = records.findByClientId(log.id(), request.clientRecordId()).orElse(null);
        if (existing != null) {
            if (!existing.requestFingerprint().equals(fingerprint))
                throw GameException.conflict("RECORD_REQUEST_CONFLICT", "같은 clientRecordId로 다른 기록을 저장할 수 없습니다.");
            if (existing.deletedAt() != null)
                throw GameException.conflict("RECORD_DELETED", "삭제한 기록입니다. 새 기록에는 새 clientRecordId를 사용해 주세요.");
            return new Registration(InningRecordResponse.from(existing), false);
        }
        games.lockGame(request.gameId());
        Game game = requireGame(request.gameId());
        if (!game.gameDate().equals(today()))
            throw GameException.conflict("RECORDING_DATE_NOT_TODAY", "새 기록은 한국 시간 기준 당일 경기만 가능합니다.");
        if (!recordableToday(game))
            throw GameException.conflict("GAME_NOT_RECORDABLE", "취소되거나 연기된 경기입니다.");
        Instant now = clock.instant();
        if (!captured.atZone(GameUserAccess.KST).toLocalDate().equals(game.gameDate()) || captured.isAfter(now))
            throw new IllegalArgumentException("촬영 시각은 해당 경기일이며 현재 시각 이후일 수 없습니다.");
        // A state remains valid until the next change. This also supports delayed uploads after the game ends.
        var state = games.stateAtOrBefore(game.id(), captured)
                .filter(snapshot -> (snapshot.status() == GameStatus.LIVE || snapshot.status() == GameStatus.FINISHED)
                        && snapshot.hasScore()).orElse(null);
        long id = records.insert(log.id(), request.clientRecordId(), fingerprint, request.inning(), request.half(), request.text(),
                captured, state == null ? null : state.homeScore(), state == null ? null : state.awayScore(),
                state == null ? null : state.observedAt(), now);
        return new Registration(InningRecordResponse.from(requireRecord(id, false)), true);
    }

    @Transactional
    public InningRecordResponse update(String subject, long recordId, String text) {
        var user = access.require(subject, true);
        var record = requireOwnedRecord(user.getId(), recordId, false);
        records.updateText(record.id(), text, clock.instant());
        return InningRecordResponse.from(requireRecord(record.id(), false));
    }

    @Transactional
    public void delete(String subject, long recordId) {
        var user = access.require(subject, true);
        var record = requireOwnedRecord(user.getId(), recordId, true);
        records.delete(record.id(), clock.instant());
    }

    private InningRecord requireOwnedRecord(long userId, long recordId, boolean includeDeleted) {
        var record = requireRecord(recordId, includeDeleted);
        if (record.userId() != userId) throw GameException.notFound("RECORD_NOT_FOUND");
        return record;
    }

    private InningRecord requireRecord(long id, boolean includeDeleted) {
        return records.find(id).filter(record -> includeDeleted || record.deletedAt() == null)
                .orElseThrow(() -> GameException.notFound("RECORD_NOT_FOUND"));
    }

    private TimelineResponse.Owner visibleOwner(long viewerId, long ownerId) {
        return records.visibleOwner(viewerId, ownerId).orElseThrow(() -> GameException.notFound("TIMELINE_NOT_FOUND"));
    }

    private Optional<UserGameLog> activeLog(long userId, long gameId) {
        return games.logForGame(userId, gameId).filter(log -> log.deletedAt() == null);
    }

    private Game requireGame(long id) {
        return games.find(id).orElseThrow(() -> GameException.notFound("GAME_NOT_FOUND"));
    }

    private boolean recordableToday(Game game) {
        return game.gameDate().equals(today()) && game.status() != GameStatus.CANCELED && game.status() != GameStatus.POSTPONED;
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(GameUserAccess.KST)); }

    private static TimelineProfilesResponse.Profile profile(TimelineResponse.Owner owner, List<TimelineProfilesResponse.GameSummary> games) {
        return new TimelineProfilesResponse.Profile(owner.userId(), owner.username(), owner.nickname(), owner.profileImageUrl(),
                games.stream().anyMatch(game -> game.recordCount() > 0), games);
    }

    private String fingerprint(CreateInningRecordRequest request, Instant captured) {
        String body = mapper.writeValueAsString(Arrays.asList(request.gameId(), request.clientRecordId(), request.inning(),
                request.half(), captured.toString(), request.text()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encodeCursor(InningRecord record) {
        String value = record.userGameLogId() + "|" + record.inning() + "|" + record.recordedAt() + "|" + record.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static Position decodeCursor(String cursor) {
        if (cursor == null) return null;
        try {
            if (cursor.length() > 256) throw new IllegalArgumentException();
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4) throw new IllegalArgumentException();
            var position = new Position(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), Instant.parse(parts[2]), Long.parseLong(parts[3]));
            if (position.logId() <= 0 || position.inning() < 1 || position.inning() > 99 || position.id() <= 0
                    || position.recordedAt().atZone(GameUserAccess.KST).getYear() < 1900
                    || position.recordedAt().atZone(GameUserAccess.KST).getYear() > 9998)
                throw new IllegalArgumentException();
            return position;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("유효하지 않은 타임라인 커서입니다.");
        }
    }

    public record Registration(InningRecordResponse response, boolean created) {}
}
