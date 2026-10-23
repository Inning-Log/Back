package com.inninglog.domain.mypage.image;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ProfileImageRepository {
    private final JdbcTemplate jdbc;
    public ProfileImageRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Upload(UUID id, long userId, String contentType, long size, String status,
                         Instant expiresAt, boolean stagingDeleted) {}

    public Optional<Upload> find(UUID id) {
        return jdbc.query("select * from profile_image_uploads where id = ?", (r, n) ->
                new Upload(r.getObject("id", UUID.class), r.getLong("user_id"), r.getString("content_type"),
                        r.getLong("size_bytes"), r.getString("status"), r.getTimestamp("expires_at").toInstant(),
                        r.getBoolean("staging_deleted")), id).stream().findFirst();
    }

    public void insert(UUID id, long userId, String type, long size, Instant now, Instant expires) {
        jdbc.update("""
                insert into profile_image_uploads(id, user_id, content_type, size_bytes, status, created_at, expires_at)
                values (?, ?, ?, ?, 'PENDING', ?, ?)
                """, id, userId, type, size, Timestamp.from(now), Timestamp.from(expires));
    }
    public void retirePending(long userId) {
        jdbc.update("update profile_image_uploads set status = 'RETIRED' where user_id = ? and status = 'PENDING'", userId);
    }
    public void retireOthers(long userId, UUID keep) {
        jdbc.update("update profile_image_uploads set status = 'RETIRED' where user_id = ? and id <> ?", userId, keep);
    }
    public void retireAll(long userId) {
        jdbc.update("update profile_image_uploads set status = 'RETIRED' where user_id = ?", userId);
    }
    public void ready(UUID id) {
        jdbc.update("update profile_image_uploads set status = 'READY' where id = ?", id);
    }
    public void stagingDeleted(UUID id) {
        jdbc.update("update profile_image_uploads set staging_deleted = true where id = ?", id);
    }
    public void delete(UUID id) {
        jdbc.update("delete from profile_image_uploads where id = ?", id);
    }
    public List<UUID> cleanupCandidates(Instant before) {
        return jdbc.query("""
                select p.id from profile_image_uploads p join app_users u on u.id = p.user_id
                where p.expires_at < ? and (
                    p.staging_deleted = false or p.status <> 'READY' or u.deleted_at is not null
                    or u.profile_image_url is null
                    or u.profile_image_url <> concat('/api/profile-images/', cast(p.id as varchar)))
                order by p.expires_at, p.id limit 50
                """, (r, n) -> r.getObject("id", UUID.class), Timestamp.from(before));
    }
}

