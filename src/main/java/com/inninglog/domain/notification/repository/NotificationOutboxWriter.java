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
public class NotificationOutboxWriter {

    private final JdbcTemplate jdbcTemplate;
    private volatile DatabaseDialect databaseDialect;

    public NotificationOutboxWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public InsertResult insertIfAbsent(
            String idempotencyKey,
            Long notificationId,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            Instant createdAt
    ) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        int insertedRows = switch (databaseDialect()) {
            case POSTGRESQL -> insertPostgresql(
                    idempotencyKey,
                    notificationId,
                    userId,
                    notificationType,
                    title,
                    body,
                    dataJson,
                    timestamp);
            case H2 -> insertH2(
                    idempotencyKey,
                    notificationId,
                    userId,
                    notificationType,
                    title,
                    body,
                    dataJson,
                    timestamp);
        };

        Long outboxId = jdbcTemplate.queryForObject(
                "select id from notification_outbox where user_id = ? and idempotency_key = ?",
                Long.class,
                userId,
                idempotencyKey);
        return new InsertResult(outboxId, insertedRows == 1);
    }

    private int insertPostgresql(
            String idempotencyKey,
            Long notificationId,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            OffsetDateTime timestamp
    ) {
        return jdbcTemplate.update("""
                insert into notification_outbox (
                    idempotency_key,
                    notification_id,
                    user_id,
                    notification_type,
                    title,
                    body,
                    data_json,
                    status,
                    created_at,
                    updated_at,
                    version
                ) values (?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, 0)
                on conflict (user_id, idempotency_key) do nothing
                """,
                idempotencyKey,
                notificationId,
                userId,
                notificationType.name(),
                title,
                body,
                dataJson,
                timestamp,
                timestamp);
    }

    private int insertH2(
            String idempotencyKey,
            Long notificationId,
            Long userId,
            NotificationType notificationType,
            String title,
            String body,
            String dataJson,
            OffsetDateTime timestamp
    ) {
        try {
            return jdbcTemplate.update("""
                    merge into notification_outbox as target
                    using (values (?, ?, ?, ?, ?, ?, ?, ?, ?)) as source(
                        idempotency_key,
                        notification_id,
                        user_id,
                        notification_type,
                        title,
                        body,
                        data_json,
                        created_at,
                        updated_at
                    )
                    on target.user_id = source.user_id
                       and target.idempotency_key = source.idempotency_key
                    when not matched then insert (
                        idempotency_key,
                        notification_id,
                        user_id,
                        notification_type,
                        title,
                        body,
                        data_json,
                        status,
                        created_at,
                        updated_at,
                        version
                    ) values (
                        source.idempotency_key,
                        source.notification_id,
                        source.user_id,
                        source.notification_type,
                        source.title,
                        source.body,
                        source.data_json,
                        'PENDING',
                        source.created_at,
                        source.updated_at,
                        0
                    )
                    """,
                    idempotencyKey,
                    notificationId,
                    userId,
                    notificationType.name(),
                    title,
                    body,
                    dataJson,
                    timestamp,
                    timestamp);
        } catch (DuplicateKeyException ignored) {
            // H2 does not re-match a concurrent MERGE after the winning transaction
            // commits. Unlike PostgreSQL, H2 keeps this transaction usable, so the
            // following SELECT can return the winner without weakening production SQL.
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
                            "Unsupported notification outbox database: " + productName);
                };
            }
            return databaseDialect;
        }
    }

    public record InsertResult(Long outboxId, boolean inserted) {
    }

    private enum DatabaseDialect {
        POSTGRESQL,
        H2
    }
}
