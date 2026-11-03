package com.inninglog.ops;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationCheckTest {
    @TempDir Path dir;

    @Test
    void pendingIsAllowedOnlyBeforeDeploymentAndDoesNotExecuteSql() throws Exception {
        String url = database();
        Files.writeString(dir.resolve("V2__next.sql"), "create table next_table(id int);");
        var preflight = check(url, true);
        MigrationCheck.validate(preflight);
        assertThat(preflight.info().applied()).hasSize(1);
        assertThatThrownBy(() -> MigrationCheck.validate(check(url, false))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsChangedAppliedVersion() throws Exception {
        String url = database();
        Files.writeString(dir.resolve("V1__initial.sql"), "create table wrong_table(id int);");
        assertThatThrownBy(() -> MigrationCheck.validate(check(url, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMissingLatestVersionInsteadOfIgnoringFuture() throws Exception {
        String url = database();
        Files.writeString(dir.resolve("V2__next.sql"), "create table next_table(id int);");
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + dir).load().migrate();
        Files.delete(dir.resolve("V2__next.sql"));
        assertThatThrownBy(() -> MigrationCheck.validate(check(url, true))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAnUninitializedDatabase() {
        assertThatThrownBy(() -> MigrationCheck.validate(check("jdbc:h2:mem:" + UUID.randomUUID(), true)))
                .isInstanceOf(IllegalStateException.class);
    }

    private String database() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        Files.writeString(dir.resolve("V1__initial.sql"), "create table initial_table(id int);");
        Flyway.configure().dataSource(url, "sa", "").locations("filesystem:" + dir).load().migrate();
        return url;
    }

    private Flyway check(String url, boolean pending) {
        return MigrationCheck.configured(url, "sa", "", "filesystem:" + dir, pending);
    }
}
