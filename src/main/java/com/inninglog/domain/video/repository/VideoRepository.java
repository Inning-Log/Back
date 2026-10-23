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
            instant(rs, "updated_at"), instant(rs, "uploaded_at"), instant(rs, "deleted_at"));

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
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into inning_record_videos(inning_record_id,object_key,content_type,size_bytes,status,created_at,updated_at)
                    values (?,?,?,?,'PENDING',?,?)
                    """, new String[]{"id"});
            Object[] values = {recordId, key, contentType, sizeBytes, timestamp(now), timestamp(now)};
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
        jdbc.update("update inning_record_videos set deleted_at = ?, updated_at = ? where id = ? and deleted_at is null",
                timestamp(now), timestamp(now), id);
    }
}
