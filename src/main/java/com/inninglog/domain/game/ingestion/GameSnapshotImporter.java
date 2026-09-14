package com.inninglog.domain.game.ingestion;

import static com.inninglog.domain.game.repository.GameRepository.timestamp;

import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.model.GameStatus;
import com.inninglog.domain.game.model.GameType;
import com.inninglog.domain.game.repository.GameRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Imports trusted crawler envelopes, never client-supplied scores from the public API. */
@Service
public class GameSnapshotImporter {
    private final JdbcTemplate jdbc;
    private final GameRepository games;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final boolean allowFixtures;
    private static final Map<String, String> TEAM_CODES = Map.of("KIA", "HT", "DOO", "OB", "SSG", "SK", "WOE", "WO");

    public GameSnapshotImporter(JdbcTemplate jdbc, GameRepository games, ObjectMapper mapper, Clock clock,
            @Value("${app.games.allow-fixtures:false}") boolean allowFixtures) {
        this.jdbc = jdbc; this.games = games; this.mapper = mapper; this.clock = clock; this.allowFixtures = allowFixtures;
    }

    @Transactional(timeout = 30)
    public ImportResult importMessage(String body) {
        if (body == null || body.getBytes(StandardCharsets.UTF_8).length > 240_000) fail("SNAPSHOT_TOO_LARGE");
        JsonNode root;
        try { root = mapper.readTree(body); }
        catch (RuntimeException exception) { throw new GameImportException("INVALID_SNAPSHOT_JSON"); }
        if (root == null || !root.isObject()) fail("INVALID_SNAPSHOT_JSON");
        JsonNode payload = root.path("payload");
        if (requiredInt(root, "schemaVersion") != 1 || requiredInt(payload, "schemaVersion") != 1) fail("UNSUPPORTED_SNAPSHOT_VERSION");
        String source = text(payload, "source", true, 30);
        if (!"kbo-pages".equals(source) && !(allowFixtures && "fixture-kbo-pages".equals(source))) fail("UNSUPPORTED_SNAPSHOT_SOURCE");
        String mode = text(payload, "mode", true, 30);
        if (!Set.of("schedule-month", "plan-day", "game-window").contains(mode)) fail("INVALID_SNAPSHOT_MODE");
        boolean monthly = "schedule-month".equals(mode);
        String expectedType = monthly ? "KBO_SCHEDULE_MONTH_SNAPSHOT" : "KBO_GAME_SNAPSHOT";
        if (!expectedType.equals(text(root, "type", true, 50))) fail("SNAPSHOT_TYPE_MISMATCH");
        LocalDate date = date(text(payload, "date", true, 10));
        YearMonth month = YearMonth.from(date);
        if (monthly && (!date.equals(month.atDay(1)) || !month.toString().equals(text(payload, "month", true, 7)))) fail("INVALID_SNAPSHOT_MONTH");
        Instant observedAt = time(payload, "observedAt", true);
        if (observedAt.isAfter(clock.instant().plusSeconds(300))) fail("FUTURE_OBSERVATION");
        JsonNode entries = payload.path("games");
        JsonNode anomalies = payload.path("anomalies");
        if (!entries.isArray() || entries.size() > 500 || !anomalies.isArray()) fail("INVALID_SNAPSHOT_COLLECTIONS");
        // Serializes receipts, missing-ID matching and inserts across all server instances.
        jdbc.queryForObject("select id from game_import_lock where id = 1 for update", Integer.class);
        Map<String, Long> teamIds = new HashMap<>();
        jdbc.query("select id, team_code from kbo_teams", rs -> { teamIds.put(rs.getString("team_code"), rs.getLong("id")); });
        List<Incoming> incoming = new ArrayList<>();
        for (JsonNode entry : entries) {
            Incoming game = normalize(entry, observedAt, mode, teamIds);
            if (monthly ? !YearMonth.from(game.date()).equals(month) : !game.date().equals(date)) fail("GAME_OUTSIDE_SNAPSHOT_SCOPE");
            incoming.add(game);
        }
        // Compute from normalized content and observation, not the publisher's reusable content hash.
        String key = digest(mapper.writeValueAsString(List.of(source, mode, date.toString(), observedAt.toString(), incoming, anomalies)));
        if (jdbc.queryForObject("select count(*) from game_snapshot_receipts where event_key = ?", Long.class, key) > 0)
            return new ImportResult(true, incoming.size(), key);
        Set<Long> matched = new HashSet<>();
        for (Incoming game : incoming) {
            long samePairCount = incoming.stream().filter(other -> other.pairKey().equals(game.pairKey())).count();
            long gameId = upsert(game, samePairCount);
            if (!matched.add(gameId)) fail("MULTIPLE_ROWS_MATCH_SAME_GAME");
        }
        String scopeKey = (monthly ? "MONTH:" + month : "DAY:" + date);
        var previous = jdbc.query("select observed_at from game_sync_scopes where scope_key = ?",
                (rs, index) -> GameRepository.instant(rs, "observed_at"), scopeKey);
        if (previous.isEmpty()) {
            jdbc.update("insert into game_sync_scopes(scope_key,state,observed_at,last_applied_at) values (?,?,?,?)",
                    scopeKey, anomalies.isEmpty() ? "IMPORTED" : "PARTIAL", timestamp(observedAt), timestamp(clock.instant()));
        } else if (observedAt.isAfter(previous.getFirst())) {
            jdbc.update("update game_sync_scopes set state = ?, observed_at = ?, last_applied_at = ? where scope_key = ?",
                    anomalies.isEmpty() ? "IMPORTED" : "PARTIAL", timestamp(observedAt), timestamp(clock.instant()), scopeKey);
        }
        jdbc.update("insert into game_snapshot_receipts(event_key,source,observed_at,processed_at) values (?,?,?,?)",
                key, source, timestamp(observedAt), timestamp(clock.instant()));
        return new ImportResult(false, incoming.size(), key);
    }

    private Incoming normalize(JsonNode entry, Instant observedAt, String mode, Map<String, Long> teams) {
        LocalDate date = date(text(entry, "date", true, 10));
        int season = entry.path("seasonYear").isMissingNode() ? date.getYear() : requiredInt(entry, "seasonYear");
        if (season < 1900 || season > 9999 || Math.abs(season - date.getYear()) > 1) fail("INVALID_SEASON");
        GameType type = enumValue(GameType.class, text(entry, "gameType", false, 30), GameType.UNKNOWN);
        // Legacy gameNumber is not equivalent to 0=normal/1,2=DH. Only trust explicit gameSequence.
        Integer sequence = integer(entry, "gameSequence");
        if (sequence != null && (sequence < 0 || sequence > 2)) fail("INVALID_GAME_SEQUENCE");
        long home = team(entry.path("homeTeam"), teams), away = team(entry.path("awayTeam"), teams);
        if (home == away) fail("IDENTICAL_TEAMS");
        String statusText = text(entry, "status", true, 20);
        GameStatus status = enumValue(GameStatus.class, "CANCELLED".equals(statusText) ? "CANCELED" : statusText, GameStatus.UNKNOWN);
        Integer homeScore = integer(entry.path("score"), "home"), awayScore = integer(entry.path("score"), "away");
        if ((homeScore != null && homeScore < 0) || (awayScore != null && awayScore < 0)) fail("NEGATIVE_SCORE");
        Integer inning = integer(entry, "inning");
        String half = text(entry, "half", false, 6);
        if ((inning == null) != (half == null) || (inning != null && inning < 1) || (half != null && !Set.of("TOP","BOTTOM").contains(half))) fail("INVALID_INNING");
        JsonNode meta = entry.path("meta");
        Instant scheduleAt = time(meta, "scheduleObservedAt", false);
        Instant resultAt = time(meta, "resultObservedAt", false);
        String resultSource = text(meta, "resultSource", false, 20);
        // A game-window can contain cached data from two differently timed polls.
        if (mode.equals("game-window") && (scheduleAt == null || resultAt == null || resultSource == null)) fail("MISSING_FIELD_OBSERVATION");
        if (scheduleAt == null) scheduleAt = observedAt;
        if (resultAt == null) resultAt = observedAt;
        if (resultSource == null) resultSource = "SCHEDULE";
        if (!Set.of("SCHEDULE", "SCOREBOARD").contains(resultSource) || scheduleAt.isAfter(observedAt) || resultAt.isAfter(observedAt)) fail("INVALID_FIELD_OBSERVATION");
        return new Incoming(season, type, date, sequence, time(entry,"scheduledAt",false), time(entry,"startedAt",false),
                time(entry,"endedAt",false), home, away, text(entry,"stadium",false,100), homeScore, awayScore, status,
                inning, half, scheduleAt, resultAt, resultSource, text(entry.path("externalId"),"kbo",false,100));
    }

    private long upsert(Incoming value, long samePairCount) {
        Game existing = null;
        if (value.externalId() != null) {
            var ids = jdbc.queryForList("select game_id from game_external_ids where provider = 'KBO' and external_id = ?", Long.class, value.externalId());
            if (!ids.isEmpty()) existing = games.find(ids.getFirst()).orElseThrow();
        }
        if (existing == null) {
            List<Game> candidates = games.forDate(value.date()).stream()
                    .filter(g -> g.homeTeam().id() == value.home() && g.awayTeam().id() == value.away())
                    .filter(g -> g.gameType() == value.type() || g.gameType() == GameType.UNKNOWN || value.type() == GameType.UNKNOWN)
                    .filter(g -> g.gameSequence() == null || value.sequence() == null || g.gameSequence().equals(value.sequence()))
                    .filter(g -> value.externalId() == null || jdbc.queryForObject("select count(*) from game_external_ids where game_id = ?", Long.class, g.id()) == 0)
                    .toList();
            var exact = candidates.stream().filter(g -> value.scheduledAt() != null && value.scheduledAt().equals(g.scheduledAt())).toList();
            var sequence = candidates.stream().filter(g -> value.sequence() != null && value.sequence().equals(g.gameSequence())).toList();
            if (sequence.size() == 1) existing = sequence.getFirst();
            else if (exact.size() == 1) existing = exact.getFirst();
            else if (!candidates.isEmpty()) {
                // A different, explicit time in a multi-game snapshot can establish a second game.
                boolean distinctTime = samePairCount > 1 && value.scheduledAt() != null
                        && candidates.stream().allMatch(g -> g.scheduledAt() != null && !value.scheduledAt().equals(g.scheduledAt()));
                if (!distinctTime) fail("AMBIGUOUS_GAME_IDENTITY");
            }
        }
        long id;
        if (existing == null) id = insert(value);
        else {
            id = existing.id();
            if (existing.homeTeam().id() != value.home() || existing.awayTeam().id() != value.away()) fail("GAME_TEAMS_CHANGED");
            if (existing.gameType() != GameType.UNKNOWN && value.type() != GameType.UNKNOWN && existing.gameType() != value.type()) fail("GAME_TYPE_CHANGED");
            update(existing, value);
        }
        if (value.externalId() != null && jdbc.queryForObject("select count(*) from game_external_ids where provider = 'KBO' and external_id = ?", Long.class, value.externalId()) == 0) {
            jdbc.update("insert into game_external_ids(game_id,provider,external_id,created_at) values (?,'KBO',?,?)", id, value.externalId(), timestamp(clock.instant()));
        }
        return id;
    }

    private long insert(Incoming value) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into games(season_year,game_type,game_date,game_sequence,scheduled_at,started_at,ended_at,
                        home_team_id,away_team_id,stadium_name_snapshot,home_score,away_score,status,current_inning,current_half,
                        schedule_observed_at,result_observed_at,result_source,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            Object[] values = {value.season(), value.type().name(), value.date(), value.sequence(), timestamp(value.scheduledAt()),
                    timestamp(value.startedAt()), timestamp(value.endedAt()), value.home(), value.away(), value.stadium(), value.homeScore(), value.awayScore(),
                    value.status().name(), value.inning(), value.half(), timestamp(value.scheduleObservedAt()), timestamp(value.resultObservedAt()),
                    value.resultSource(), timestamp(clock.instant()), timestamp(clock.instant())};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    private void update(Game old, Incoming value) {
        if (value.scheduleObservedAt().isAfter(old.scheduleObservedAt())) {
            jdbc.update("""
                    update games set season_year=?, game_type=?, game_date=?, game_sequence=?,
                        scheduled_at=?, stadium_name_snapshot=?, schedule_observed_at=?, updated_at=? where id=?
                    """, value.season(), value.type() == GameType.UNKNOWN ? old.gameType().name() : value.type().name(), value.date(),
                    value.sequence() == null ? old.gameSequence() : value.sequence(), timestamp(or(value.scheduledAt(),old.scheduledAt())),
                    or(value.stadium(),old.stadiumName()), timestamp(value.scheduleObservedAt()), timestamp(clock.instant()), old.id());
        }
        boolean newer = value.resultObservedAt().isAfter(old.resultObservedAt());
        boolean sameTime = value.resultObservedAt().equals(old.resultObservedAt());
        boolean higherPriority = value.resultSource().equals("SCOREBOARD") && !old.resultSource().equals("SCOREBOARD");
        if (!newer && !(sameTime && higherPriority)) {
            if (sameTime && old.resultSource().equals(value.resultSource()) &&
                    (old.status() != value.status() || !Objects.equals(old.homeScore(),value.homeScore()) || !Objects.equals(old.awayScore(),value.awayScore())))
                fail("CONFLICTING_RESULT_OBSERVATION");
            return;
        }
        // A schedule-only pregame row must not undo an observed live/final result.
        if ((old.status() == GameStatus.LIVE || old.status() == GameStatus.FINISHED)
                && (value.status() == GameStatus.SCHEDULED || value.status() == GameStatus.UNKNOWN)) return;
        if (old.status() == GameStatus.FINISHED && value.status() != GameStatus.FINISHED && value.status() != GameStatus.CANCELED) return;
        if (old.status() == GameStatus.FINISHED && (value.homeScore() == null || value.awayScore() == null) && value.status() == GameStatus.FINISHED) return;
        jdbc.update("""
                update games set status=?,home_score=?,away_score=?,current_inning=?,current_half=?,
                    started_at=?,ended_at=?,result_observed_at=?,result_source=?,updated_at=? where id=?
                """, value.status().name(), value.homeScore(), value.awayScore(), value.inning(), value.half(),
                timestamp(or(value.startedAt(),old.startedAt())), timestamp(or(value.endedAt(),old.endedAt())),
                timestamp(value.resultObservedAt()), value.resultSource(), timestamp(clock.instant()), old.id());
    }

    private static long team(JsonNode node, Map<String, Long> teams) {
        String code = text(node,"code",true,10).toUpperCase(Locale.ROOT);
        Long id = teams.get(TEAM_CODES.getOrDefault(code,code));
        if (id == null) fail("UNMAPPED_TEAM");
        return id;
    }
    private static String text(JsonNode node, String key, boolean required, int max) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) {
            if (required) fail("MISSING_" + key);
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) fail("INVALID_" + key);
        return value.asText();
    }
    private static Integer integer(JsonNode node, String key) {
        JsonNode value = node.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt()) fail("INVALID_" + key);
        return value.intValue();
    }
    private static int requiredInt(JsonNode node, String key) {
        Integer value = integer(node,key);
        if (value == null) fail("MISSING_" + key);
        return value;
    }
    private static LocalDate date(String text) {
        try {
            LocalDate date = LocalDate.parse(text);
            if (date.getYear() < 1900 || date.getYear() > 9998 || !date.toString().equals(text)) fail("INVALID_DATE");
            return date;
        } catch (RuntimeException exception) { throw new GameImportException("INVALID_DATE"); }
    }
    private static Instant time(JsonNode node, String key, boolean required) {
        String value = text(node,key,required,40);
        if (value == null) return null;
        try { return Instant.parse(value).truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
        catch (RuntimeException exception) { throw new GameImportException("INVALID_" + key); }
    }
    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, E fallback) {
        if (value == null) return fallback;
        try { return Enum.valueOf(type,value); }
        catch (IllegalArgumentException exception) { throw new GameImportException("INVALID_" + type.getSimpleName()); }
    }
    private static <T> T or(T value, T fallback) { return value == null ? fallback : value; }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void fail(String code) { throw new GameImportException(code); }
    public record ImportResult(boolean duplicate, int gameCount, String eventKey) {}
    public record Incoming(int season, GameType type, LocalDate date, Integer sequence, Instant scheduledAt, Instant startedAt,
                           Instant endedAt, long home, long away, String stadium, Integer homeScore, Integer awayScore,
                           GameStatus status, Integer inning, String half, Instant scheduleObservedAt, Instant resultObservedAt,
                           String resultSource, String externalId) {
        String pairKey() { return date + ":" + home + ":" + away; }
    }
}
