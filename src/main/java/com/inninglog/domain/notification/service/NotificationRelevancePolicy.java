package com.inninglog.domain.notification.service;

import com.inninglog.domain.notification.entity.NotificationOutbox;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rechecked for every attempt, including retries after a process restart. */
@Component
public class NotificationRelevancePolicy {
    private final JdbcTemplate jdbc;
    private final NotificationPayloadCodec codec;

    public NotificationRelevancePolicy(JdbcTemplate jdbc, NotificationPayloadCodec codec) {
        this.jdbc = jdbc;
        this.codec = codec;
    }

    public Instant expiresAt(NotificationOutbox outbox) {
        Duration lifetime = switch (outbox.getNotificationType()) {
            case GAME_INNING_STARTED, GAME_INNING_ENDED, GAME_SCORE_CHANGED -> Duration.ofMinutes(10);
            case RECORD_REMINDER -> Duration.ofHours(1);
            default -> Duration.ofDays(7);
        };
        return outbox.getCreatedAt().plus(lifetime);
    }

    public String cancellationReason(NotificationOutbox outbox, Instant now) {
        if (!now.isBefore(expiresAt(outbox))) return "EXPIRED";
        Map<String, String> data;
        try { data = codec.decodeData(outbox.getDataJson()); }
        catch (RuntimeException ex) { return "INVALID_PAYLOAD"; }
        if (outbox.getNotificationType() == com.inninglog.domain.notification.entity.NotificationType.FRIEND_REQUEST
                && outbox.getIdempotencyKey().startsWith("friendship:")) {
            if (!exists("""
                    select count(*) from friendships f
                    join app_users u on u.id = f.requester_id and u.deleted_at is null
                    where f.receiver_id = ? and f.status = 'PENDING'
                      and concat('friendship:', cast(f.id as varchar), ':requested:',
                                 cast(f.request_revision as varchar)) = ?
                    """, outbox.getUserId(), outbox.getIdempotencyKey())) return "REQUEST_RESOLVED";
        }
        if (data.containsKey("userGameLogId")) {
            if (!exists("""
                    select count(*) from user_game_logs l
                    join app_users u on u.id = l.user_id and u.deleted_at is null
                    where l.id = ? and l.user_id = ? and l.game_id = ? and l.deleted_at is null
                    """, numeric(data, "userGameLogId"), outbox.getUserId(), numeric(data, "gameId")))
                return "VIEWING_REMOVED";
            if (outbox.getNotificationType() == com.inninglog.domain.notification.entity.NotificationType.RECORD_REMINDER
                    && exists("""
                    select count(*) from inning_records where user_game_log_id = ?
                      and inning_number = ? and deleted_at is null
                    """, numeric(data, "userGameLogId"), numeric(data, "inning"))) return "ALREADY_RECORDED";
        }
        if (outbox.getNotificationType() == com.inninglog.domain.notification.entity.NotificationType.TIMELINE_COMMENT
                && data.containsKey("commentId")) {
            if (!exists("""
                    select count(*) from video_comments c
                    join inning_record_videos v on v.id = c.video_id and v.deleted_at is null and v.status = 'READY'
                    join inning_records r on r.id = v.inning_record_id and r.deleted_at is null
                    join user_game_logs l on l.id = r.user_game_log_id and l.deleted_at is null
                    join app_users a on a.id = c.author_user_id and a.deleted_at is null
                    where c.id = ? and c.deleted_at is null and l.user_id = ?
                      and exists (select 1 from friendships f where f.status = 'ACCEPTED'
                        and ((f.requester_id = a.id and f.receiver_id = l.user_id)
                          or (f.receiver_id = a.id and f.requester_id = l.user_id)))
                    """, numeric(data, "commentId"), outbox.getUserId())) return "COMMENT_UNAVAILABLE";
        }
        return null;
    }

    private boolean exists(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args) > 0;
    }

    private static long numeric(Map<String, String> data, String key) {
        try { return Long.parseLong(data.get(key)); }
        catch (RuntimeException ex) { return -1; }
    }
}
