package com.inninglog.domain.video.repository;

import static com.inninglog.domain.game.repository.GameRepository.instant;
import static com.inninglog.domain.game.repository.GameRepository.timestamp;

import com.inninglog.domain.video.model.InningRecordVideo;
import com.inninglog.domain.video.model.VideoStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class VideoRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<InningRecordVideo> MAPPER = (rs, index) -> new InningRecordVideo(
            rs.getLong("id"), rs.getLong("inning_record_id"), rs.getString("object_key"), rs.getString("content_type"),
            rs.getLong("size_bytes"), VideoStatus.valueOf(rs.getString("status")), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "uploaded_at"), instant(rs, "deleted_at"),
            instant(rs, "upload_expires_at") == null ? instant(rs, "created_at").plus(java.time.Duration.ofDays(7))
                    : instant(rs, "upload_expires_at"));

    public VideoRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Serializes upload/complete/delete for one record so that it keeps at most one active video. */
    public void lockRecord(long recordId) {
        jdbc.queryForList("select id from inning_records where id = ? for update", Long.class, recordId);
    }

    public Optional<InningRecordVideo> findActive(long recordId) {
        return jdbc.query("""
                select * from inning_record_videos where inning_record_id = ? and deleted_at is null
                order by id desc limit 1
                """, MAPPER, recordId).stream().findFirst();
    }

    public List<InningRecordVideo> findAllActive(long recordId) {
        return jdbc.query("select * from inning_record_videos where inning_record_id = ? and deleted_at is null",
                MAPPER, recordId);
    }

    public long insertPending(long recordId, String key, String contentType, long sizeBytes, Instant now) {
        return insertPending(recordId, key, contentType, sizeBytes, now, now.plus(java.time.Duration.ofMinutes(10)));
    }

    public long insertPending(long recordId, String key, String contentType, long sizeBytes, Instant now, Instant uploadExpiresAt) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into inning_record_videos(inning_record_id,object_key,content_type,size_bytes,status,created_at,updated_at,upload_expires_at)
                    values (?,?,?,?,'PENDING',?,?,?)
                    """, new String[]{"id"});
            Object[] values = {recordId, key, contentType, sizeBytes, timestamp(now), timestamp(now), timestamp(uploadExpiresAt)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    public void markReady(long id, Instant now) {
        jdbc.update("update inning_record_videos set status = 'READY', uploaded_at = ?, updated_at = ? where id = ? and deleted_at is null",
                timestamp(now), timestamp(now), id);
    }

    public void delete(long id, Instant now) {
        jdbc.update("update inning_record_videos set deleted_at = ?, updated_at = ?, cleanup_retry_at = null where id = ? and deleted_at is null",
                timestamp(now), timestamp(now), id);
    }

    private static final String CLEANUP_JOIN = """
            from inning_record_videos v join inning_records r on r.id = v.inning_record_id
            join user_game_logs l on l.id = r.user_game_log_id join app_users u on u.id = l.user_id
            """;
    // Game logs can be restored when viewing is registered again; their READY videos must survive.
    private static final String INACTIVE_PARENT = "(r.deleted_at is not null or u.deleted_at is not null)";

    public List<Long> cleanupCandidates(Instant now, Instant expiredBefore) {
        return jdbc.queryForList("select v.id " + CLEANUP_JOIN + """
                where v.storage_deleted_at is null and (v.cleanup_retry_at is null or v.cleanup_retry_at <= ?)
                and (v.deleted_at is not null or
                """ + INACTIVE_PARENT + """
                 or (v.status = 'PENDING' and coalesce(v.upload_expires_at, v.created_at + interval '7' day) <= ?))
                order by v.id limit 50
                """, Long.class, timestamp(now), timestamp(expiredBefore));
    }

    public Optional<CleanupRow> cleanupRow(long id) {
        return jdbc.query("select v.*, l.user_id owner_id, l.id log_id, " + INACTIVE_PARENT + " inactive_parent "
                + CLEANUP_JOIN + " where v.id = ?", (rs, row) -> new CleanupRow(MAPPER.mapRow(rs, row),
                        rs.getLong("owner_id"), rs.getLong("log_id"), rs.getBoolean("inactive_parent"),
                        instant(rs, "storage_deleted_at"), instant(rs, "cleanup_retry_at"), rs.getInt("cleanup_attempts")),
                id).stream().findFirst();
    }

    public void lockForCleanup(CleanupRow row) {
        // Same order as comment writes and account/record deletion; re-read all eligibility after waiting.
        jdbc.queryForList("select id from app_users where id = ? for update", Long.class, row.ownerId());
        jdbc.queryForList("select id from user_game_logs where id = ? for update", Long.class, row.logId());
        lockRecord(row.video().inningRecordId());
        jdbc.queryForList("select id from inning_record_videos where id = ? for update", Long.class, row.video().id());
    }

    public void storageDeleted(long id, Instant now) {
        jdbc.update("update inning_record_videos set storage_deleted_at = ?, cleanup_retry_at = null where id = ?",
                timestamp(now), id);
    }

    public void retryCleanup(long id, Instant retryAt, boolean failed) {
        jdbc.update("update inning_record_videos set cleanup_retry_at = ?, cleanup_attempts = cleanup_attempts + ? where id = ?",
                timestamp(retryAt), failed ? 1 : 0, id);
    }

    public record CleanupRow(InningRecordVideo video, long ownerId, long logId, boolean inactiveParent,
                             Instant storageDeletedAt, Instant retryAt, int attempts) {}
}
