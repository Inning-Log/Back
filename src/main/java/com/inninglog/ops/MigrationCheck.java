package com.inninglog.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.ObjectMapper;

/** Standalone deployment command. Never starts Spring, migrates, repairs, or cleans a database. */
public final class MigrationCheck {
    private MigrationCheck() {}

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[0].equals("preflight") || args[0].equals("snapshot")))
                throw new IllegalArgumentException("Expected preflight|snapshot and output path");
            String revision = required("DEPLOYMENT_REVISION");
            if (!revision.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Invalid revision");
            String url = required("SPRING_DATASOURCE_URL");
            if (!url.startsWith("jdbc:postgresql:")) throw new IllegalArgumentException("PostgreSQL required");
            Flyway flyway = configured(url, required("SPRING_DATASOURCE_USERNAME"),
                    required("SPRING_DATASOURCE_PASSWORD"), "classpath:db/migration", args[0].equals("preflight"));
            validate(flyway);
            var state = new LinkedHashMap<String, Object>();
            state.put("schemaVersion", 1);
            state.put("revision", revision);
            state.put("verifiedAt", Instant.now().toString());
            state.put("mode", args[0]);
            state.put("applied", Arrays.stream(flyway.info().applied()).map(m -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("version", m.getVersion() == null ? null : m.getVersion().toString());
                item.put("script", m.getScript());
                item.put("checksum", m.getChecksum());
                return item;
            }).toList());
            Files.writeString(Path.of(args[1]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(state));
            System.out.println("Migration history verified (" + args[0] + ").");
        } catch (Exception error) {
            // Do not print JDBC URLs, credentials, SQL bodies, or arbitrary driver exception messages.
            System.err.println("Migration check failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static Flyway configured(String url, String user, String password, String location, boolean allowPending) {
        var configuration = Flyway.configure().dataSource(url, user, password).locations(location)
                .cleanDisabled(true).baselineOnMigrate(false).outOfOrder(false)
                // Replace Flyway's default *:future: an older candidate must not pass validation.
                .ignoreMigrationPatterns(allowPending ? new String[]{"*:pending"} : new String[0]);
        if (url.startsWith("jdbc:postgresql:"))
            configuration.initSql("SET default_transaction_read_only = on; SET statement_timeout = '30s'; SET lock_timeout = '5s'");
        return configuration.load();
    }

    static void validate(Flyway flyway) {
        var result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            for (var invalid : result.invalidMigrations)
                System.err.println("Migration " + invalid.version + ": " + invalid.errorDetails.errorCode);
            throw new IllegalStateException("Migration history is incompatible");
        }
        if (flyway.info().applied().length == 0)
            throw new IllegalStateException("No deployed migration history; bootstrap must be explicit");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + name);
        return value;
    }
}
