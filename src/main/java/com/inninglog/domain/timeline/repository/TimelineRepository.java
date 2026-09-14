package com.inninglog.domain.timeline.repository;

import static com.inninglog.domain.game.repository.GameRepository.instant;
import static com.inninglog.domain.game.repository.GameRepository.timestamp;

import com.inninglog.domain.game.model.ViewingType;
import com.inninglog.domain.timeline.dto.TimelineProfilesResponse.GameSummary;
import com.inninglog.domain.timeline.dto.TimelineResponse.Owner;
import com.inninglog.domain.timeline.model.InningHalf;
import com.inninglog.domain.timeline.model.InningRecord;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class TimelineRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT_RECORD = """
            select r.*, l.user_id, l.game_id from inning_records r
            join user_game_logs l on l.id = r.user_game_log_id
            where l.deleted_at is null
            """;
    private static final RowMapper<InningRecord> RECORD_MAPPER = (rs, index) -> new InningRecord(
            rs.getLong("id"), rs.getLong("user_game_log_id"), rs.getLong("user_id"), rs.getLong("game_id"),
            UUID.fromString(rs.getString("client_record_id")), rs.getString("request_fingerprint"),
            rs.getInt("inning_number"), rs.getString("half") == null ? null : InningHalf.valueOf(rs.getString("half")),
            rs.getString("caption"), instant(rs, "recorded_at"), rs.getObject("home_score_at_recording", Integer.class),
            rs.getObject("away_score_at_recording", Integer.class), instant(rs, "score_observed_at"),
            instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at"));
    private static final RowMapper<Owner> OWNER_MAPPER = (rs, index) -> new Owner(
            rs.getLong("id"), rs.getString("username"), rs.getString("nickname"), rs.getString("profile_image_url"));

    public TimelineRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Owner> visibleOwner(long viewerId, long ownerId) {
        return jdbc.query("""
                select u.id, u.username, u.nickname, u.profile_image_url from app_users u
                where u.id = ? and u.deleted_at is null and u.onboarding_completed = true
                  and (u.id = ? or exists (select 1 from friendships f where f.status = 'ACCEPTED'
                    and ((f.requester_id = ? and f.receiver_id = u.id)
                      or (f.receiver_id = ? and f.requester_id = u.id))))
                """, OWNER_MAPPER, ownerId, viewerId, viewerId, viewerId).stream().findFirst();
    }

    public List<Owner> friends(long viewerId, long afterUserId, int limit) {
        return jdbc.query("""
                select u.id, u.username, u.nickname, u.profile_image_url from app_users u
                where u.id > ? and u.id <> ? and u.deleted_at is null and u.onboarding_completed = true
                  and exists (select 1 from friendships f where f.status = 'ACCEPTED'
                    and ((f.requester_id = ? and f.receiver_id = u.id)
                      or (f.receiver_id = ? and f.requester_id = u.id)))
                order by u.id limit ?
                """, OWNER_MAPPER, afterUserId, viewerId, viewerId, viewerId, limit);
    }

    public Map<Long, List<GameSummary>> profileGames(List<Long> userIds, LocalDate date) {
        if (userIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(userIds.size(), "?"));
        List<Object> args = new ArrayList<>(userIds);
        args.add(date);
        Map<Long, List<GameSummary>> result = new HashMap<>();
        jdbc.query("""
                select l.user_id, l.game_id, l.viewing_type, count(r.id) total
                  from user_game_logs l join games g on g.id = l.game_id
                  left join inning_records r on r.user_game_log_id = l.id and r.deleted_at is null
                 where l.deleted_at is null and l.user_id in (
                """ + placeholders + ") and g.game_date = ? group by l.user_id, l.game_id, l.viewing_type order by l.game_id",
                rs -> {
                    result.computeIfAbsent(rs.getLong("user_id"), key -> new ArrayList<>()).add(
                            new GameSummary(rs.getLong("game_id"), ViewingType.valueOf(rs.getString("viewing_type")), rs.getLong("total")));
                }, args.toArray());
        return result;
    }

    public Optional<InningRecord> find(long id) {
        return jdbc.query(SELECT_RECORD + " and r.id = ?", RECORD_MAPPER, id).stream().findFirst();
    }

    public Optional<InningRecord> findByClientId(long logId, UUID clientId) {
        return jdbc.query(SELECT_RECORD + " and r.user_game_log_id = ? and r.client_record_id = ?",
                RECORD_MAPPER, logId, clientId.toString()).stream().findFirst();
    }

    public List<InningRecord> page(long logId, Position after, int limit) {
        String sql = SELECT_RECORD + " and r.user_game_log_id = ? and r.deleted_at is null";
        List<Object> args = new ArrayList<>();
        args.add(logId);
        if (after != null) {
            sql += """
                     and (r.inning_number > ? or (r.inning_number = ? and r.recorded_at > ?)
                       or (r.inning_number = ? and r.recorded_at = ? and r.id > ?))
                    """;
            args.addAll(List.of(after.inning(), after.inning(), timestamp(after.recordedAt()),
                    after.inning(), timestamp(after.recordedAt()), after.id()));
        }
        args.add(limit);
        return jdbc.query(sql + " order by r.inning_number, r.recorded_at, r.id limit ?", RECORD_MAPPER, args.toArray());
    }

    public long count(long logId) {
        return jdbc.queryForObject("select count(*) from inning_records where user_game_log_id = ? and deleted_at is null", Long.class, logId);
    }

    public long insert(long logId, UUID clientId, String fingerprint, int inning, InningHalf half, String text,
                       Instant recordedAt, Integer homeScore, Integer awayScore, Instant scoreObservedAt, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into inning_records(user_game_log_id,client_record_id,request_fingerprint,inning_number,half,
                        caption,recorded_at,home_score_at_recording,away_score_at_recording,score_observed_at,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            Object[] values = {logId, clientId.toString(), fingerprint, inning, half == null ? null : half.name(), text,
                    timestamp(recordedAt), homeScore, awayScore, timestamp(scoreObservedAt), timestamp(now), timestamp(now)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public void updateText(long id, String text, Instant now) {
        jdbc.update("update inning_records set caption = ?, updated_at = ? where id = ? and deleted_at is null", text, timestamp(now), id);
    }

    public void delete(long id, Instant now) {
        jdbc.update("update inning_records set deleted_at = ?, updated_at = ? where id = ? and deleted_at is null", timestamp(now), timestamp(now), id);
    }

    public record Position(long logId, int inning, Instant recordedAt, long id) {}
}
