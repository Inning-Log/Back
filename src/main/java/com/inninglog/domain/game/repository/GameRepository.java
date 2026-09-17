package com.inninglog.domain.game.repository;

import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.model.GameStatus;
import com.inninglog.domain.game.model.GameStateSnapshot;
import com.inninglog.domain.game.model.GameType;
import com.inninglog.domain.game.model.UserGameLog;
import com.inninglog.domain.game.model.ViewingType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class GameRepository {
    private final JdbcTemplate jdbc;
    private static final String GAME_SELECT = """
            select g.*, h.team_code h_code, h.name h_name, h.short_name h_short, h.logo_url h_logo,
                   a.team_code a_code, a.name a_name, a.short_name a_short, a.logo_url a_logo
              from games g join kbo_teams h on h.id = g.home_team_id
                           join kbo_teams a on a.id = g.away_team_id
            """;
    private static final RowMapper<Game> GAME_MAPPER = (rs, index) -> new Game(
            rs.getLong("id"), rs.getInt("season_year"), GameType.valueOf(rs.getString("game_type")),
            rs.getObject("game_date", LocalDate.class), rs.getObject("game_sequence", Integer.class),
            instant(rs, "scheduled_at"), instant(rs, "started_at"), instant(rs, "ended_at"),
            new Game.Team(rs.getLong("home_team_id"), rs.getString("h_code"), rs.getString("h_name"), rs.getString("h_short"), rs.getString("h_logo")),
            new Game.Team(rs.getLong("away_team_id"), rs.getString("a_code"), rs.getString("a_name"), rs.getString("a_short"), rs.getString("a_logo")),
            rs.getString("stadium_name_snapshot"), rs.getObject("home_score", Integer.class),
            rs.getObject("away_score", Integer.class), GameStatus.valueOf(rs.getString("status")),
            rs.getObject("current_inning", Integer.class), rs.getString("current_half"),
            instant(rs, "schedule_observed_at"), instant(rs, "result_observed_at"), rs.getString("result_source"));
    private static final RowMapper<UserGameLog> LOG_MAPPER = (rs, index) -> new UserGameLog(
            rs.getLong("id"), rs.getLong("user_id"), rs.getLong("game_id"), rs.getLong("cheering_team_id"),
            ViewingType.valueOf(rs.getString("viewing_type")), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at"));
    private static final RowMapper<GameStateSnapshot> STATE_MAPPER = (rs, index) -> new GameStateSnapshot(
            rs.getLong("id"), rs.getLong("game_id"), GameStatus.valueOf(rs.getString("status")),
            rs.getObject("current_inning", Integer.class), rs.getString("current_half"),
            rs.getObject("home_score", Integer.class), rs.getObject("away_score", Integer.class),
            instant(rs, "observed_at"), rs.getString("result_source"));

    public GameRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Game> find(long id) {
        return jdbc.query(GAME_SELECT + " where g.id = ?", GAME_MAPPER, id).stream().findFirst();
    }

    public void lockGame(long id) {
        jdbc.queryForList("select id from games where id = ? for update", Long.class, id);
    }

    public List<Game> forDate(LocalDate date) {
        return jdbc.query(GAME_SELECT + " where g.game_date = ? order by g.scheduled_at nulls last, g.game_sequence nulls last, g.id",
                GAME_MAPPER, date);
    }

    public List<GameStateSnapshot> stateHistory(long gameId) {
        return jdbc.query("""
                select * from game_state_snapshots
                 where game_id = ? order by observed_at, id
                """, STATE_MAPPER, gameId);
    }

    public Optional<GameStateSnapshot> stateAtOrBefore(long gameId, Instant instant) {
        return jdbc.query("""
                select * from game_state_snapshots
                 where game_id = ? and observed_at <= ?
                 order by observed_at desc, id desc limit 1
                """, STATE_MAPPER, gameId, timestamp(instant)).stream().findFirst();
    }

    public List<Game> forMonth(long userId, long teamId, LocalDate from, LocalDate until) {
        return jdbc.query(GAME_SELECT + """
                 where g.game_date >= ? and g.game_date < ?
                   and (g.home_team_id = ? or g.away_team_id = ? or exists (
                       select 1 from user_game_logs l where l.game_id = g.id and l.user_id = ? and l.deleted_at is null))
                 order by g.game_date, g.scheduled_at nulls last, g.game_sequence nulls last, g.id
                """, GAME_MAPPER, from, until, teamId, teamId, userId);
    }

    public List<UserGameLog> logsForRange(long userId, LocalDate from, LocalDate until) {
        return jdbc.query("""
                select l.* from user_game_logs l join games g on g.id = l.game_id
                 where l.user_id = ? and l.deleted_at is null and g.game_date >= ? and g.game_date < ?
                """, LOG_MAPPER, userId, from, until);
    }

    public Optional<UserGameLog> logForGame(long userId, long gameId) {
        return jdbc.query("select * from user_game_logs where user_id = ? and game_id = ?", LOG_MAPPER, userId, gameId).stream().findFirst();
    }

    public Optional<UserGameLog> logById(long userId, long id) {
        return jdbc.query("select * from user_game_logs where user_id = ? and id = ?", LOG_MAPPER, userId, id).stream().findFirst();
    }

    public long createLog(long userId, long gameId, long cheeringTeamId, ViewingType type, Instant now) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into user_game_logs(user_id,game_id,cheering_team_id,viewing_type,created_at,updated_at)
                    values (?,?,?,?,?,?)
                    """, new String[]{"id"});
            statement.setLong(1, userId);
            statement.setLong(2, gameId);
            statement.setLong(3, cheeringTeamId);
            statement.setString(4, type.name());
            statement.setObject(5, timestamp(now));
            statement.setObject(6, timestamp(now));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public void updateLog(long id, ViewingType type, Instant now) {
        jdbc.update("update user_game_logs set viewing_type = ?, updated_at = ? where id = ?", type.name(), timestamp(now), id);
    }

    public void restoreLog(long id, long cheeringTeamId, ViewingType type, Instant now) {
        jdbc.update("update user_game_logs set cheering_team_id = ?, viewing_type = ?, deleted_at = null, updated_at = ? where id = ?",
                cheeringTeamId, type.name(), timestamp(now), id);
    }

    public void deleteLog(long id, Instant now) {
        jdbc.update("update inning_records set deleted_at = ?, updated_at = ? where user_game_log_id = ? and deleted_at is null",
                timestamp(now), timestamp(now), id);
        jdbc.update("update user_game_logs set deleted_at = ?, updated_at = ? where id = ? and deleted_at is null", timestamp(now), timestamp(now), id);
    }

    public void deleteUserLogs(long userId, Instant now) {
        jdbc.update("""
                update inning_records set deleted_at = ?, updated_at = ?
                 where deleted_at is null and user_game_log_id in (select id from user_game_logs where user_id = ?)
                """, timestamp(now), timestamp(now), userId);
        jdbc.update("update user_game_logs set deleted_at = ?, updated_at = ? where user_id = ? and deleted_at is null", timestamp(now), timestamp(now), userId);
    }

    public Map<Long, Long> recordCountsForRange(long userId, LocalDate from, LocalDate until) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("""
                select l.game_id, count(r.id) total from user_game_logs l
                join games g on g.id = l.game_id join inning_records r on r.user_game_log_id = l.id
                where l.user_id = ? and l.deleted_at is null and r.deleted_at is null
                  and g.game_date >= ? and g.game_date < ? group by l.game_id
                """, rs -> { result.put(rs.getLong("game_id"), rs.getLong("total")); }, userId, from, until);
        return result;
    }

    public SyncState syncState(String key) {
        return jdbc.query("select state, last_applied_at from game_sync_scopes where scope_key = ?",
                (rs, index) -> new SyncState(rs.getString(1), instant(rs, "last_applied_at")), key)
                .stream().findFirst().orElse(new SyncState("NOT_IMPORTED", null));
    }

    /** Count one row per viewing, irrespective of the number of later video records. */
    public List<ResultCount> resultCounts(long userId, int seasonYear) {
        return jdbc.query("""
                select result, count(*) total from (
                    select case
                        when g.status in ('CANCELED','POSTPONED') then 'VOID'
                        when g.status in ('SCHEDULED','LIVE','DELAYED','SUSPENDED') then 'PENDING'
                        when g.status <> 'FINISHED' or g.home_score is null or g.away_score is null then 'UNKNOWN'
                        when l.cheering_team_id not in (g.home_team_id, g.away_team_id) then 'UNKNOWN'
                        when g.home_score = g.away_score then 'DRAW'
                        when (l.cheering_team_id = g.home_team_id and g.home_score > g.away_score)
                          or (l.cheering_team_id = g.away_team_id and g.away_score > g.home_score) then 'WIN'
                        else 'LOSS' end result
                      from user_game_logs l join games g on g.id = l.game_id
                     where l.user_id = ? and l.deleted_at is null and l.viewing_type = 'STADIUM'
                       and g.season_year = ? and g.game_type = 'REGULAR'
                ) results group by result
                """, (rs, index) -> new ResultCount(rs.getString(1), rs.getLong(2)), userId, seasonYear);
    }

    public long unclassifiedCount(long userId, int year) {
        return jdbc.queryForObject("""
                select count(*) from user_game_logs l join games g on g.id = l.game_id
                 where l.user_id = ? and l.deleted_at is null and l.viewing_type = 'STADIUM'
                   and g.season_year = ? and g.game_type = 'UNKNOWN'
                """, Long.class, userId, year);
    }

    public record SyncState(String state, Instant lastAppliedAt) {}
    public record ResultCount(String result, long count) {}

    public static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
