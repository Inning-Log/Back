package com.inninglog.domain.gamevideo.repository;

import static com.inninglog.domain.game.repository.GameRepository.instant;

import com.inninglog.domain.gamevideo.model.ClipSource;
import com.inninglog.domain.gamevideo.model.Participant;
import com.inninglog.domain.timeline.model.InningHalf;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Reads the inning record videos and profiles that go into a merged game video. */
@Repository
public class GameVideoSourceRepository {
    private static final RowMapper<Participant> PARTICIPANT = (rs, index) -> new Participant(
            rs.getLong("id"), rs.getString("username"), rs.getString("nickname"), rs.getString("profile_image_url"),
            rs.getString("team_code"), rs.getString("team_name"), rs.getLong("clip_count"));
    private static final RowMapper<ClipSource> CLIP = (rs, index) -> {
        String half = rs.getString("half");
        return new ClipSource(rs.getLong("user_id"), rs.getLong("record_id"), rs.getInt("inning_number"),
                half == null ? null : InningHalf.valueOf(half), rs.getString("caption"), instant(rs, "recorded_at"),
                (Integer) rs.getObject("home_score_at_recording"), (Integer) rs.getObject("away_score_at_recording"),
                rs.getString("object_key"));
    };
    private static final String READY_CLIPS = """
            (select count(*) from inning_records r join inning_record_videos v on v.inning_record_id = r.id
             where r.user_game_log_id = l.id and r.deleted_at is null and v.deleted_at is null and v.status = 'READY')
            """;

    private final JdbcTemplate jdbc;

    public GameVideoSourceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Accepted friends who watched this game and have at least one finished clip in it. */
    public List<Participant> friendsWithClips(long userId, long gameId) {
        return jdbc.query("""
                select * from (
                  select u.id, u.username, u.nickname, u.profile_image_url, t.team_code, t.short_name team_name,
                    %s clip_count
                  from app_users u
                  join user_game_logs l on l.user_id = u.id and l.game_id = ? and l.deleted_at is null
                  join kbo_teams t on t.id = l.cheering_team_id
                  where u.id <> ? and u.deleted_at is null and u.onboarding_completed = true
                    and exists (select 1 from friendships f where f.status = 'ACCEPTED'
                      and ((f.requester_id = ? and f.receiver_id = u.id) or (f.receiver_id = ? and f.requester_id = u.id)))
                ) candidates where clip_count > 0
                order by coalesce(nickname, username), id
                """.formatted(READY_CLIPS), PARTICIPANT, gameId, userId, userId, userId);
    }

    /** Profiles of users who logged this game, in no particular order. */
    public List<Participant> participants(long gameId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return jdbc.query("""
                select u.id, u.username, u.nickname, u.profile_image_url, t.team_code, t.short_name team_name,
                  %s clip_count
                from app_users u
                join user_game_logs l on l.user_id = u.id and l.game_id = ? and l.deleted_at is null
                join kbo_teams t on t.id = l.cheering_team_id
                where u.deleted_at is null and u.id in (%s)
                """.formatted(READY_CLIPS, placeholders(userIds)), PARTICIPANT, args(gameId, userIds));
    }

    /** Finished clips of these users in this game, in capture order within each inning. */
    public List<ClipSource> clips(long gameId, Collection<Long> userIds) {
        if (userIds.isEmpty()) return List.of();
        return jdbc.query("""
                select l.user_id, r.id record_id, r.inning_number, r.half, r.caption, r.recorded_at,
                  r.home_score_at_recording, r.away_score_at_recording, v.object_key
                from inning_records r
                join user_game_logs l on l.id = r.user_game_log_id
                join inning_record_videos v on v.inning_record_id = r.id and v.deleted_at is null and v.status = 'READY'
                where l.game_id = ? and l.deleted_at is null and r.deleted_at is null and l.user_id in (%s)
                order by r.inning_number, r.recorded_at, r.id
                """.formatted(placeholders(userIds)), CLIP, args(gameId, userIds));
    }

    private static String placeholders(Collection<Long> ids) {
        return ids.stream().map(id -> "?").collect(Collectors.joining(","));
    }

    private static Object[] args(long gameId, Collection<Long> ids) {
        Object[] values = new Object[ids.size() + 1];
        values[0] = gameId;
        int i = 1;
        for (Long id : ids) values[i++] = id;
        return values;
    }
}
