package com.inninglog.domain.gamevideo.repository;

import static com.inninglog.domain.game.repository.GameRepository.instant;
import static com.inninglog.domain.game.repository.GameRepository.timestamp;

import com.inninglog.domain.gamevideo.model.GameVideo;
import com.inninglog.domain.gamevideo.model.GameVideoMode;
import com.inninglog.domain.gamevideo.model.GameVideoStatus;
import com.inninglog.domain.gamevideo.model.SegmentRender;
import com.inninglog.domain.gamevideo.model.SegmentStatus;
import com.inninglog.domain.timeline.model.InningHalf;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class GameVideoRepository {
    private static final RowMapper<GameVideo> VIDEO = (rs, index) -> new GameVideo(
            rs.getLong("id"), rs.getLong("user_id"), rs.getLong("game_id"), GameVideoMode.valueOf(rs.getString("mode")),
            GameVideoStatus.valueOf(rs.getString("status")), rs.getString("object_key"), rs.getInt("segment_count"),
            rs.getBigDecimal("duration_seconds"), rs.getString("failure_reason"), instant(rs, "requested_at"),
            instant(rs, "merge_requested_at"), instant(rs, "completed_at"), instant(rs, "deleted_at"));
    private static final RowMapper<SegmentRender> RENDER = (rs, index) -> new SegmentRender(
            rs.getLong("id"), rs.getString("render_key"), rs.getString("object_key"),
            SegmentStatus.valueOf(rs.getString("status")), rs.getBigDecimal("duration_seconds"),
            rs.getString("failure_reason"), rs.getInt("attempts"), instant(rs, "requested_at"), instant(rs, "completed_at"));

    private final JdbcTemplate jdbc;

    public GameVideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // ---- game_videos ----

    public Optional<GameVideo> findActive(long userId, long gameId) {
        return jdbc.query("""
                select * from game_videos where user_id = ? and game_id = ? and deleted_at is null
                order by id desc limit 1
                """, VIDEO, userId, gameId).stream().findFirst();
    }

    public List<GameVideo> activeForUser(long userId) {
        return jdbc.query("select * from game_videos where user_id = ? and deleted_at is null for update", VIDEO, userId);
    }

    public Optional<GameVideo> lockActive(long id) {
        return jdbc.query("select * from game_videos where id = ? and deleted_at is null for update", VIDEO, id)
                .stream().findFirst();
    }

    public Optional<GameVideo> find(long id) {
        return jdbc.query("select * from game_videos where id = ?", VIDEO, id).stream().findFirst();
    }

    public long insertVideo(long userId, long gameId, GameVideoMode mode, String objectKey, int segmentCount, Instant now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into game_videos(user_id,game_id,mode,status,object_key,segment_count,requested_at,created_at,updated_at)
                    values (?,?,?,'RENDERING',?,?,?,?,?)
                    """, new String[]{"id"});
            Object[] values = {userId, gameId, mode.name(), objectKey, segmentCount, timestamp(now), timestamp(now), timestamp(now)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    public void insertMember(long videoId, int position, long userId) {
        jdbc.update("insert into game_video_members(game_video_id,position,user_id) values (?,?,?)", videoId, position, userId);
    }

    public void insertSegment(long videoId, int position, long renderId, int inning, InningHalf half) {
        jdbc.update("insert into game_video_segments(game_video_id,position,segment_render_id,inning_number,half) values (?,?,?,?,?)",
                videoId, position, renderId, inning, half == null ? null : half.name());
    }

    public List<Long> memberIds(long videoId) {
        return jdbc.queryForList("select user_id from game_video_members where game_video_id = ? order by position",
                Long.class, videoId);
    }

    /** Segment object keys in playback order. */
    public List<SegmentRender> segments(long videoId) {
        return jdbc.query("""
                select r.* from game_video_segments s join segment_renders r on r.id = s.segment_render_id
                where s.game_video_id = ? order by s.position
                """, RENDER, videoId);
    }

    /** Active videos that are still waiting on this segment. */
    public List<Long> renderingVideosUsing(long renderId) {
        return jdbc.queryForList("""
                select distinct v.id from game_videos v join game_video_segments s on s.game_video_id = v.id
                where s.segment_render_id = ? and v.status = 'RENDERING' and v.deleted_at is null order by v.id
                """, Long.class, renderId);
    }

    public void markMerging(long id, Instant now) {
        jdbc.update("update game_videos set status = 'MERGING', merge_requested_at = ?, updated_at = ? where id = ?",
                timestamp(now), timestamp(now), id);
    }

    public void markReady(long id, BigDecimal duration, Instant now) {
        jdbc.update("""
                update game_videos set status = 'READY', duration_seconds = ?, failure_reason = null, completed_at = ?, updated_at = ?
                where id = ?
                """, duration, timestamp(now), timestamp(now), id);
    }

    public void markFailed(long id, String reason, Instant now) {
        jdbc.update("update game_videos set status = 'FAILED', failure_reason = ?, completed_at = ?, updated_at = ? where id = ?",
                truncate(reason), timestamp(now), timestamp(now), id);
    }

    public void delete(long id, Instant now) {
        jdbc.update("update game_videos set deleted_at = ?, updated_at = ? where id = ? and deleted_at is null",
                timestamp(now), timestamp(now), id);
    }

    /** RENDERING videos whose segments are all READY but whose merge was never requested (a lost publish). */
    public List<Long> renderingWithAllSegmentsReady() {
        return jdbc.queryForList("""
                select v.id from game_videos v where v.status = 'RENDERING' and v.deleted_at is null
                  and not exists (select 1 from game_video_segments s join segment_renders r on r.id = s.segment_render_id
                                  where s.game_video_id = v.id and r.status <> 'READY')
                order by v.id
                """, Long.class);
    }

    public List<Long> mergingRequestedBefore(Instant cutoff) {
        return jdbc.queryForList("""
                select id from game_videos where status = 'MERGING' and deleted_at is null and merge_requested_at < ? order by id
                """, Long.class, timestamp(cutoff));
    }

    // ---- segment_renders ----

    public Optional<SegmentRender> findRender(String renderKey) {
        return jdbc.query("select * from segment_renders where render_key = ?", RENDER, renderKey).stream().findFirst();
    }

    public Optional<SegmentRender> lockRender(String renderKey) {
        return jdbc.query("select * from segment_renders where render_key = ? for update", RENDER, renderKey).stream().findFirst();
    }

    public long insertRender(String renderKey, String objectKey, Instant now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into segment_renders(render_key,object_key,status,requested_at,created_at,updated_at)
                    values (?,?,'PROCESSING',?,?,?)
                    """, new String[]{"id"});
            Object[] values = {renderKey, objectKey, timestamp(now), timestamp(now), timestamp(now)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    public void restartRender(long id, Instant now) {
        jdbc.update("""
                update segment_renders set status = 'PROCESSING', failure_reason = null, duration_seconds = null,
                  completed_at = null, attempts = attempts + 1, requested_at = ?, updated_at = ? where id = ?
                """, timestamp(now), timestamp(now), id);
    }

    public boolean markRenderReady(long id, BigDecimal duration, Instant now) {
        return jdbc.update("""
                update segment_renders set status = 'READY', duration_seconds = ?, failure_reason = null, completed_at = ?, updated_at = ?
                where id = ? and status <> 'READY'
                """, duration, timestamp(now), timestamp(now), id) == 1;
    }

    public boolean markRenderFailed(long id, String reason, Instant now) {
        return jdbc.update("""
                update segment_renders set status = 'FAILED', failure_reason = ?, completed_at = ?, updated_at = ?
                where id = ? and status = 'PROCESSING'
                """, truncate(reason), timestamp(now), timestamp(now), id) == 1;
    }

    public List<String> processingRenderKeysRequestedBefore(Instant cutoff) {
        return jdbc.queryForList("select render_key from segment_renders where status = 'PROCESSING' and requested_at < ? order by id",
                String.class, timestamp(cutoff));
    }

    private static String truncate(String reason) {
        if (reason == null) return null;
        return reason.length() <= 100 ? reason : reason.substring(0, 100);
    }
}
