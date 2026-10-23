package com.inninglog.domain.comment.repository;

import static com.inninglog.domain.game.repository.GameRepository.instant;
import static com.inninglog.domain.game.repository.GameRepository.timestamp;

import com.inninglog.domain.comment.dto.CommentResponse;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class VideoCommentRepository {
    private final JdbcTemplate jdbc;
    private static final String SELECT = """
            select c.*, u.username, u.nickname, u.profile_image_url from video_comments c
            join app_users u on u.id = c.author_user_id and u.deleted_at is null
            """;
    private static final String PARENT_VISIBLE = """
             and (c.parent_comment_id is null or exists (
                 select 1 from video_comments p join app_users pu on pu.id = p.author_user_id
                 where p.id = c.parent_comment_id and p.video_id = c.video_id
                   and p.deleted_at is null and pu.deleted_at is null))
            """;
    private static final RowMapper<Comment> MAPPER = (rs, row) -> new Comment(
            rs.getLong("id"), rs.getLong("video_id"), rs.getLong("author_user_id"),
            rs.getString("request_fingerprint"), rs.getString("content"), instant(rs, "created_at"),
            instant(rs, "updated_at"), instant(rs, "deleted_at"), rs.getObject("parent_comment_id", Long.class),
            new CommentResponse.Author(rs.getLong("author_user_id"), rs.getString("username"),
                    rs.getString("nickname"), rs.getString("profile_image_url")));

    public VideoCommentRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean activeUser(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from app_users where id = ? and deleted_at is null
                    and onboarding_completed = true)
                """, Boolean.class, id));
    }

    public Optional<Video> video(long id) {
        return jdbc.query("""
                select v.id, r.id record_id, l.id log_id, l.game_id, l.user_id owner_id
                  from inning_record_videos v join inning_records r on r.id = v.inning_record_id
                  join user_game_logs l on l.id = r.user_game_log_id
                  join app_users u on u.id = l.user_id
                 where v.id = ? and v.status = 'READY' and v.deleted_at is null
                   and r.deleted_at is null and l.deleted_at is null and u.deleted_at is null
                   and u.onboarding_completed = true
                """, (rs, row) -> new Video(rs.getLong("id"), rs.getLong("record_id"),
                rs.getLong("log_id"), rs.getLong("game_id"), rs.getLong("owner_id")), id).stream().findFirst();
    }

    /** Matches user ordering in friendship creation and locks parents before checking them again. */
    public void lockParents(long viewerId, Video video) {
        lockParents(viewerId, video, video.ownerId());
    }

    public void lockParents(long viewerId, Video video, long parentAuthorId) {
        // Lock a reply recipient before any content row, in the same global user ordering.
        // This also serializes account deletion and friendship changes for the recipient.
        LongStream.of(viewerId, video.ownerId(), parentAuthorId).distinct().sorted().forEach(id ->
                jdbc.queryForList("select id from app_users where id = ? for update", Long.class, id));
        if (viewerId != video.ownerId()) {
            jdbc.queryForList("""
                    select id from friendships where (requester_id = ? and receiver_id = ?)
                       or (requester_id = ? and receiver_id = ?) for update
                    """, Long.class, viewerId, video.ownerId(), video.ownerId(), viewerId);
        }
        jdbc.queryForList("select id from user_game_logs where id = ? for update", Long.class, video.logId());
        jdbc.queryForList("select id from inning_records where id = ? for update", Long.class, video.recordId());
        jdbc.queryForList("select id from inning_record_videos where id = ? for update", Long.class, video.id());
    }

    public Optional<Comment> find(long id) {
        return jdbc.query(SELECT + " where c.id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Comment> findByClient(long videoId, long authorId, UUID clientId) {
        return jdbc.query(SELECT + " where c.video_id = ? and c.author_user_id = ? and c.client_comment_id = ?",
                MAPPER, videoId, authorId, clientId.toString()).stream().findFirst();
    }

    public List<Comment> page(long videoId, long afterId, int limit) {
        return jdbc.query(SELECT + " where c.video_id = ? and c.deleted_at is null" + PARENT_VISIBLE
                        + " and c.id > ? order by c.id limit ?",
                MAPPER, videoId, afterId, limit);
    }

    public long count(long videoId) {
        return jdbc.queryForObject("""
                select count(*) from video_comments c join app_users u on u.id = c.author_user_id
                where c.video_id = ? and c.deleted_at is null and u.deleted_at is null
                """ + PARENT_VISIBLE, Long.class, videoId);
    }

    public long insert(long videoId, long authorId, UUID clientId, String fingerprint, String content,
                       Long parentCommentId, Instant now) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("""
                    insert into video_comments(video_id,author_user_id,client_comment_id,request_fingerprint,content,parent_comment_id,created_at,updated_at)
                    values (?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
            Object[] values = {videoId, authorId, clientId.toString(), fingerprint, content, parentCommentId, timestamp(now), timestamp(now)};
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, holder);
        return holder.getKey().longValue();
    }

    public void update(long id, String content, Instant now) {
        jdbc.update("update video_comments set content = ?, updated_at = ? where id = ? and deleted_at is null",
                content, timestamp(now), id);
    }

    public void delete(long id, Instant now) {
        // Keep only an empty tombstone for idempotency; do not retain deleted text.
        jdbc.update("""
                update video_comments set content = '[삭제됨]', deleted_at = ?, updated_at = ?
                where (id = ? or parent_comment_id = ?) and deleted_at is null
                """, timestamp(now), timestamp(now), id, id);
    }

    public record Video(long id, long recordId, long logId, long gameId, long ownerId) {}
    public record Comment(long id, long videoId, long authorId, String fingerprint, String content,
                          Instant createdAt, Instant updatedAt, Instant deletedAt, Long parentCommentId, CommentResponse.Author author) {
        public CommentResponse response(long viewerId) {
            return new CommentResponse(id, videoId, author, content, createdAt, updatedAt, authorId == viewerId, parentCommentId);
        }
    }
}
