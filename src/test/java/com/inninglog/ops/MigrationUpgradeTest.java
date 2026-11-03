package com.inninglog.ops;

import static org.assertj.core.api.Assertions.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against a disposable PostgreSQL service, never production. */
@EnabledIfEnvironmentVariable(named = "MIGRATION_TEST_JDBC_URL", matches = "jdbc:postgresql:.*")
class MigrationUpgradeTest {
    @Test
    void upgradesFromVerifiedDeploymentAndValidatesReadOnly() throws Exception {
        String url = System.getenv("MIGRATION_TEST_JDBC_URL");
        String user = System.getenv("MIGRATION_TEST_DB_USER");
        String password = System.getenv("MIGRATION_TEST_DB_PASSWORD");
        String baseline = System.getenv("MIGRATION_BASELINE_DIR");
        assertThat(baseline).isNotBlank();
        var old = Flyway.configure().dataSource(url, user, password).locations("filesystem:" + baseline).load();
        old.migrate();
        MigrationCheck.validate(MigrationCheck.configured(url, user, password, "classpath:db/migration", true));
        var candidate = Flyway.configure().dataSource(url, user, password).locations("classpath:db/migration")
                .ignoreMigrationPatterns(new String[0]).load();
        candidate.migrate();
        var check = MigrationCheck.configured(url, user, password, "classpath:db/migration", false);
        MigrationCheck.validate(check);
        try (var connection = check.getConfiguration().getDataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute(check.getConfiguration().getInitSql());
            assertThatThrownBy(() -> statement.execute("create table forbidden_write(id int)"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("read-only");
        }
    }
}
