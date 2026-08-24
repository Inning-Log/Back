package com.inninglog.domain.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class NotificationMigrationTest {

    @Test
    void v11KeepsTheLatestRegistrationBeforeAddingTheGlobalFidConstraint() throws Exception {
        String databaseName = "notification_migration_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl = "jdbc:h2:mem:" + databaseName
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        // Keep one H2 connection alive while two Flyway instances run. H2 2.4 can
        // otherwise retain a compiled CHECK expression tied to the closed session.
        try (Connection keepAlive = DriverManager.getConnection(jdbcUrl, "sa", "")) {
            Flyway.configure()
                    .dataSource(jdbcUrl, "sa", "")
                    .target("10")
                    .load()
                    .migrate();

            dropH2CheckConstraints(keepAlive, "user_push_tokens");
            try (Statement statement = keepAlive.createStatement()) {
                statement.executeUpdate("""
                        insert into app_users
                            (id, email, role, onboarding_completed, created_at, updated_at, version)
                        values
                            (1001, 'migration-a@example.com', 'USER', false, current_timestamp, current_timestamp, 0),
                            (1002, 'migration-b@example.com', 'USER', false, current_timestamp, current_timestamp, 0)
                        """);
                statement.executeUpdate("""
                        insert into user_push_tokens
                            (id, user_id, platform, device_id, push_token, enabled,
                             last_seen_at, created_at, updated_at)
                        values
                            (2001, 1001, 'ANDROID', 'duplicate-fid', 'old-token', true,
                             timestamp with time zone '2026-08-23 00:00:00+00', current_timestamp, current_timestamp),
                            (2002, 1002, 'ANDROID', 'duplicate-fid', 'new-token', false,
                             timestamp with time zone '2026-08-24 00:00:00+00', current_timestamp, current_timestamp)
                        """);
            }

            Flyway.configure()
                    .dataSource(jdbcUrl, "sa", "")
                    .load()
                    .migrate();

            try (Statement statement = keepAlive.createStatement();
                 ResultSet result = statement.executeQuery("""
                         select id, user_id, push_token, enabled
                           from user_push_tokens
                          where device_id = 'duplicate-fid'
                         """)) {
                assertThat(result.next()).isTrue();
                assertThat(result.getLong("id")).isEqualTo(2002L);
                assertThat(result.getLong("user_id")).isEqualTo(1002L);
                assertThat(result.getString("push_token")).isEqualTo("new-token");
                assertThat(result.getBoolean("enabled")).isFalse();
                assertThat(result.next()).isFalse();
            }
        }
    }

    private static void dropH2CheckConstraints(Connection connection, String tableName) throws Exception {
        List<String> constraintNames = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     select constraint_name
                       from information_schema.table_constraints
                      where table_name = 'user_push_tokens'
                        and constraint_type = 'CHECK'
                     """)) {
            while (result.next()) {
                constraintNames.add(result.getString("constraint_name"));
            }
        }
        for (String constraintName : constraintNames) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("alter table " + tableName + " drop constraint \""
                        + constraintName.replace("\"", "\"\"") + "\"");
            }
        }
    }
}
