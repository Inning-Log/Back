package com.inninglog.domain.notification.repository;

import com.inninglog.domain.notification.entity.NotificationType;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationInboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private volatile DatabaseDialect databaseDialect;

    public NotificationInboxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public InsertResult insertIfAbsent(
            String eventKey,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            Instant createdAt
    ) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        int insertedRows = switch (databaseDialect()) {
            case POSTGRESQL -> jdbcTemplate.update("""
                    insert into notifications (
                        event_key, user_id, notification_type, title, body, data_json, created_at
                    ) values (?, ?, ?, ?, ?, ?, ?)
                    on conflict (user_id, event_key) do nothing
                    """, eventKey, userId, notificationType.name(), title, body, dataJson, timestamp);
            case H2 -> insertH2(eventKey, userId, notificationType, title, body, dataJson, timestamp);
        };

        Long notificationId = jdbcTemplate.queryForObject(
                "select id from notifications where user_id = ? and event_key = ?",
                Long.class,
                userId,
                eventKey);
        return new InsertResult(notificationId, insertedRows == 1);
    }

    private int insertH2(
            String eventKey,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            OffsetDateTime timestamp
    ) {
        try {
            return jdbcTemplate.update("""
                    merge into notifications as target
                    using (values (?, ?, ?, ?, ?, ?, ?)) as source(
                        event_key, user_id, notification_type, title, body, data_json, created_at
                    )
                    on target.user_id = source.user_id and target.event_key = source.event_key
                    when not matched then insert (
                        event_key, user_id, notification_type, title, body, data_json, created_at
                    ) values (
                        source.event_key, source.user_id, source.notification_type,
                        source.title, source.body, source.data_json, source.created_at
                    )
                    """, eventKey, userId, notificationType.name(), title, body, dataJson, timestamp);
        } catch (DuplicateKeyException ignored) {
            return 0;
        }
    }

    private DatabaseDialect databaseDialect() {
        DatabaseDialect resolved = databaseDialect;
        if (resolved != null) {
            return resolved;
        }
        synchronized (this) {
            if (databaseDialect == null) {
                String productName = jdbcTemplate.execute((ConnectionCallback<String>) connection ->
                        connection.getMetaData().getDatabaseProductName());
                databaseDialect = switch (productName) {
                    case "PostgreSQL" -> DatabaseDialect.POSTGRESQL;
                    case "H2" -> DatabaseDialect.H2;
                    default -> throw new IllegalStateException(
                            "Unsupported notification inbox database: " + productName);
                };
            }
            return databaseDialect;
        }
    }

    public record InsertResult(Long notificationId, boolean inserted) {
    }

    private enum DatabaseDialect {
        POSTGRESQL,
        H2
    }
}
