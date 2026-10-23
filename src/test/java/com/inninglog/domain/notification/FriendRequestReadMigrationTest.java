package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class FriendRequestReadMigrationTest {
    @Test
    void upgradeReadsHistoricalResolvedRequestsAndPreservesCurrentPendingAndOtherAlerts() throws Exception {
        String migrationSuffix = UUID.randomUUID().toString().replace("-", "");
        String configuredUrl = System.getenv("MIGRATION_TEST_JDBC_URL");
        String url = configuredUrl != null ? configuredUrl : "jdbc:h2:mem:friend_read_" + migrationSuffix
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String user = configuredUrl == null ? "sa" : System.getenv().getOrDefault("MIGRATION_TEST_DB_USER", "postgres");
        String password = configuredUrl == null ? "" : System.getenv().getOrDefault("MIGRATION_TEST_DB_PASSWORD", "");
        String schema = configuredUrl == null ? "public" : "friend_read_" + migrationSuffix;
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            // H2 2.4 CHECK expressions keep their creating session; retain it across both migrations.
            var dataSource = new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true);
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("19").load().migrate();
            connection.setSchema(schema);
            try (Statement sql = connection.createStatement()) {
                for (int id = 1001; id <= 1006; id++) {
                    sql.executeUpdate("""
                            insert into app_users(id,email,role,onboarding_completed,created_at,updated_at,version)
                            values (%d,'migration%d@example.com','USER',false,current_timestamp,current_timestamp,0)
                            """.formatted(id, id));
                }
                sql.executeUpdate("""
                        insert into friendships(id,requester_id,receiver_id,pair_key,status,request_revision,
                                                requested_at,responded_at,created_at,updated_at,version)
                        values
                        (2001,1001,1002,'1001:1002','ACCEPTED',1,current_timestamp,current_timestamp,current_timestamp,current_timestamp,0),
                        (2002,1003,1002,'1002:1003','REJECTED',1,current_timestamp,current_timestamp,current_timestamp,current_timestamp,0),
                        (2003,1004,1002,'1002:1004','PENDING',2,current_timestamp,null,current_timestamp,current_timestamp,0),
                        (2004,1005,1002,'1002:1005','PENDING',1,current_timestamp,null,current_timestamp,current_timestamp,0)
                        """);
                sql.executeUpdate("update app_users set deleted_at=current_timestamp where id=1005");
            }
            insert(connection, 3001, 1002, "FRIEND_REQUEST", "friendship:2001:requested:1", null);
            insert(connection, 3002, 1002, "FRIEND_REQUEST", "friendship:2002:requested:1", null);
            insert(connection, 3003, 1002, "FRIEND_REQUEST", "friendship:9999:requested:1", null);
            insert(connection, 3004, 1002, "FRIEND_REQUEST", "friendship:2003:requested:1", null);
            insert(connection, 3005, 1002, "FRIEND_REQUEST", "friendship:2003:requested:2", null);
            insert(connection, 3006, 1002, "TIMELINE_COMMENT", "video:123:comment:4", null);
            insert(connection, 3007, 1002, "FRIEND_ACCEPTED", "friendship:2001:accepted:1", null);
            OffsetDateTime originalReadAt = OffsetDateTime.parse("2026-01-01T00:00:00Z");
            insert(connection, 3008, 1002, "FRIEND_REQUEST", "friendship:2001:requested:9", originalReadAt);
            insert(connection, 3009, 1002, "FRIEND_REQUEST", "friendship:2004:requested:1", null);
            insert(connection, 3010, 1006, "FRIEND_REQUEST", "friendship:2003:requested:2", null);

            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).target("20").load().migrate();

            for (long id : new long[]{3001, 3002, 3003, 3004, 3009, 3010}) {
                assertThat(readAt(connection, id)).as("resolved request %s", id).isNotNull();
            }
            for (long id : new long[]{3005, 3006, 3007}) {
                assertThat(readAt(connection, id)).as("preserved alert %s", id).isNull();
            }
            assertThat(readAt(connection, 3008)).isEqualTo(originalReadAt);
            try (Statement sql = connection.createStatement();
                 ResultSet rows = sql.executeQuery("select count(*) from notifications where user_id=1002 and read_at is null")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isEqualTo(3);
            }
        }
    }

    private void insert(Connection connection, long id, long userId, String type, String key,
                        OffsetDateTime readAt) throws Exception {
        try (PreparedStatement sql = connection.prepareStatement("""
                insert into notifications(id,event_key,user_id,notification_type,title,data_json,read_at,created_at)
                values (?,?,?,?, '알림', '{}', ?, current_timestamp)
                """)) {
            sql.setLong(1, id);
            sql.setString(2, key);
            sql.setLong(3, userId);
            sql.setString(4, type);
            sql.setObject(5, readAt);
            sql.executeUpdate();
        }
    }

    private OffsetDateTime readAt(Connection connection, long id) throws Exception {
        try (PreparedStatement sql = connection.prepareStatement("select read_at from notifications where id=?")) {
            sql.setLong(1, id);
            try (ResultSet rows = sql.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getObject(1, OffsetDateTime.class);
            }
        }
    }
}
