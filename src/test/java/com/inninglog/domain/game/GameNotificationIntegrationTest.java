package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.inninglog.domain.user.entity.User;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GameNotificationIntegrationTest extends GameIntegrationSupport {
    @Test
    void baselineAndUnchangedObservationsDoNotNotifyOrRemind() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        window(input, OBSERVED.plusSeconds(1));
        window(input, OBSERVED.plusSeconds(2));
        assertThat(count("notifications")).isZero();
    }

    @Test
    void adjacentHalfNotifiesOnlyActiveViewersAndOneReminderPerFullInning() {
        var input = live(3, "TOP", 1, 2);
        long id = baseline(input);
        User home = user(), stadium = user(), removed = user(), withdrawn = user();
        viewing(home, id, team("LG"), "HOME");
        viewing(stadium, id, team("OB"), "STADIUM");
        long removedLog = viewing(removed, id, team("LG"), "HOME");
        viewing(withdrawn, id, team("LG"), "HOME");
        sqlUpdate("update user_game_logs set deleted_at = ? where id = ?", NOW, removedLog);
        sqlUpdate("update app_users set deleted_at = ? where id = ?", NOW, withdrawn.getId());
        user(); // A favorite-team fan with no viewing log is not a recipient.

        input.put("half", "BOTTOM");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(2);
        assertThat(typeCount("GAME_INNING_STARTED")).isEqualTo(2);
        assertThat(typeCount("RECORD_REMINDER")).isZero();

        input.put("inning", 4); input.put("half", "TOP");
        String body = window(input, OBSERVED.plusSeconds(2));
        importer.importMessage(body);
        window(input, OBSERVED.plusSeconds(3));
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(4);
        assertThat(typeCount("GAME_INNING_STARTED")).isEqualTo(4);
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(2);
        assertThat(jdbc.queryForList("select distinct user_id from notifications", Long.class))
                .containsExactlyInAnyOrder(home.getId(), stadium.getId());
    }

    @Test
    void activeRecordSuppressesReminderButDeletedRecordDoesNot() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        User recorded = user(), deletedRecord = user();
        long recordedLog = viewing(recorded, id, team("LG"), "HOME");
        long deletedLog = viewing(deletedRecord, id, team("LG"), "HOME");
        record(recordedLog, 3, false);
        record(deletedLog, 3, true);
        input.put("inning", 4); input.put("half", "TOP");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(jdbc.queryForList("select user_id from notifications where notification_type = 'RECORD_REMINDER'", Long.class))
                .containsExactly(deletedRecord.getId());
    }

    @Test
    void scoreCorrectionCyclesAreSeparateEventsButHalfCorrectionsCannotRepeatReminder() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("OB"), "STADIUM");
        input.put("score", Map.of("home", 2, "away", 2));
        window(input, OBSERVED.plusSeconds(1));
        input.put("score", Map.of("home", 1, "away", 2));
        window(input, OBSERVED.plusSeconds(2));
        input.put("score", Map.of("home", 2, "away", 2));
        window(input, OBSERVED.plusSeconds(3));
        assertThat(typeCount("GAME_SCORE_CHANGED")).isEqualTo(3);
        String scoreBody = jdbc.queryForObject("select body from notifications where notification_type = 'GAME_SCORE_CHANGED' order by id desc limit 1", String.class);
        assertThat(scoreBody).contains(games.find(id).orElseThrow().awayTeam().name(), games.find(id).orElseThrow().homeTeam().name(), "2 : 2");

        input.put("inning", 4); input.put("half", "TOP");
        window(input, OBSERVED.plusSeconds(4));
        input.put("inning", 3); input.put("half", "BOTTOM");
        window(input, OBSERVED.plusSeconds(5));
        input.put("inning", 4); input.put("half", "TOP");
        window(input, OBSERVED.plusSeconds(6));
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_STARTED")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(1);
    }

    @Test
    void settingsSuppressPushWhileRetainingInboxAndNoPushRegistrationIsRequired() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        User muted = user();
        viewing(muted, id, team("LG"), "HOME");
        sqlUpdate("insert into notification_settings(user_id, game_progress_enabled, record_reminder_enabled, social_reaction_enabled, updated_at) values (?,false,false,true,?)", muted.getId(), NOW);
        input.put("inning", 4); input.put("half", "TOP"); input.put("score", Map.of("home", 2, "away", 2));
        window(input, OBSERVED.plusSeconds(1));
        assertThat(count("notifications")).isEqualTo(4);
        assertThat(count("notification_outbox")).isZero();
    }

    @Test
    void finalStateEndsObservedHalfAndRemindsOnlyTheLastObservedInning() {
        var input = live(9, "TOP", 5, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("status", "FINISHED"); input.remove("inning"); input.remove("half");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(1);
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_STARTED")).isZero();
        assertThat(jdbc.queryForObject("select data_json from notifications where notification_type = 'RECORD_REMINDER'", String.class)).contains("\"inning\":\"9\"");
        input.put("score", Map.of("home", 4, "away", 2));
        window(input, OBSERVED.plusSeconds(2));
        assertThat(typeCount("GAME_SCORE_CHANGED")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(1);
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(1);
    }

    @Test
    void firstPitchFromScheduleNotifiesButDoesNotInventAPreviousInning() {
        var input = game(TODAY, "SCHEDULED", null, null);
        long id = ingest(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("status", "LIVE"); input.put("inning", 1); input.put("half", "TOP"); input.put("score", Map.of("home", 0, "away", 0));
        window(input, OBSERVED.plusSeconds(1));
        assertThat(typeCount("GAME_INNING_STARTED")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_ENDED")).isZero();
        assertThat(typeCount("RECORD_REMINDER")).isZero();
        assertThat(typeCount("GAME_SCORE_CHANGED")).isZero();
    }

    @Test
    void skippedInningsAreNeverBackfilled() {
        var input = live(2, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("inning", 5); input.put("half", "TOP");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(count("notifications")).isZero();
    }

    @Test
    void cancellationAndHistoricalImportsDoNotNotify() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("status", "CANCELED");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(count("notifications")).isZero();
        var historical = live(3, "BOTTOM", 1, 2);
        historical.put("date", TODAY.minusDays(1).toString());
        long historicalId = baseline(historical);
        viewing(user(), historicalId, team("LG"), "HOME");
        historical.put("inning", 4); historical.put("half", "TOP");
        window(historical, OBSERVED.plusSeconds(2));
        assertThat(count("notifications")).isZero();
    }

    @Test
    void monthlyPlanDayStaleAndScheduleSourceNeverGenerateAlerts() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("inning", 4); input.put("half", "TOP");
        // A late older observation does not change effective state.
        window(input, OBSERVED.minusSeconds(1));
        input.put("meta", Map.of("resultSource", "SCOREBOARD"));
        importer.importMessage(message(List.of(input), OBSERVED.plusSeconds(1), true));
        input.put("half", "BOTTOM");
        importer.importMessage(message(List.of(input), OBSERVED.plusSeconds(2), false));
        input.put("inning", 5); input.put("half", "TOP");
        window(input, OBSERVED.plusSeconds(3), "SCHEDULE");
        assertThat(count("notifications")).isZero();
    }

    @Test
    void delayedQueueReplayBeyondFreshnessLimitDoesNotNotify() {
        var input = live(3, "BOTTOM", 1, 2);
        window(input, NOW.minusSeconds(900));
        long id = id(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("inning", 4); input.put("half", "TOP"); input.put("score", Map.of("home", 2, "away", 2));
        window(input, NOW.minusSeconds(700));
        assertThat(count("notifications")).isZero();
    }

    @Test
    void scoreboardPromotionWithoutHistoryChangeStillAllowsTheNextTransition() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = ingest(input); // Last history row is sourced from SCHEDULE.
        viewing(user(), id, team("LG"), "HOME");
        window(input, OBSERVED.plusSeconds(1)); // Same values, now verified by SCOREBOARD.
        assertThat(games.stateHistory(id)).hasSize(1);
        assertThat(count("notifications")).isZero();
        input.put("inning", 4); input.put("half", "TOP"); input.put("score", Map.of("home", 2, "away", 2));
        window(input, OBSERVED.plusSeconds(2));
        assertThat(typeCount("GAME_INNING_STARTED")).isEqualTo(1);
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(1);
        assertThat(typeCount("GAME_SCORE_CHANGED")).isEqualTo(1);
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(1);
    }

    @Test
    void finalStateWithDifferentInningDoesNotBackfillTheLastKnownInning() {
        var input = live(3, "BOTTOM", 1, 2);
        long id = baseline(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("status", "FINISHED"); input.put("inning", 9); input.put("half", "BOTTOM");
        window(input, OBSERVED.plusSeconds(1));
        assertThat(typeCount("GAME_INNING_ENDED")).isZero();
        assertThat(typeCount("RECORD_REMINDER")).isZero();
    }

    @Test
    void finalStateWithoutInningDoesNotBackfillAfterAnObservationGap() {
        var input = live(3, "BOTTOM", 1, 2);
        window(input, NOW.minusSeconds(901));
        long id = id(input);
        viewing(user(), id, team("LG"), "HOME");
        input.put("status", "FINISHED"); input.remove("inning"); input.remove("half");
        window(input, NOW.minusSeconds(1));
        assertThat(typeCount("GAME_INNING_ENDED")).isZero();
        assertThat(typeCount("RECORD_REMINDER")).isZero();
    }

    @Test
    void unchangedRecentScoreboardHeartbeatMakesTheFinalInningObservable() {
        var input = live(9, "BOTTOM", 1, 2);
        window(input, NOW.minusSeconds(901));
        long id = id(input);
        viewing(user(), id, team("LG"), "HOME");
        window(input, NOW.minusSeconds(2));
        assertThat(games.stateHistory(id)).hasSize(1);
        input.put("status", "FINISHED"); input.remove("inning"); input.remove("half");
        window(input, NOW.minusSeconds(1));
        assertThat(typeCount("GAME_INNING_ENDED")).isEqualTo(1);
        assertThat(typeCount("RECORD_REMINDER")).isEqualTo(1);
    }

    private Map<String, Object> live(int inning, String half, int homeScore, int awayScore) {
        var input = game(TODAY, "LIVE", homeScore, awayScore);
        input.put("inning", inning); input.put("half", half);
        return input;
    }

    private long baseline(Map<String, Object> input) { window(input, OBSERVED); return id(input); }
    private long id(Map<String, Object> input) {
        return jdbc.queryForObject("select game_id from game_external_ids where external_id = ?", Long.class,
                ((Map<?, ?>) input.get("externalId")).get("kbo"));
    }
    private String window(Map<String, Object> input, Instant observed) { return window(input, observed, "SCOREBOARD"); }
    private String window(Map<String, Object> input, Instant observed, String source) {
        input.put("meta", Map.of("scheduleObservedAt", observed.toString(), "resultObservedAt", observed.toString(), "resultSource", source));
        String body = mapper.writeValueAsString(Map.of("schemaVersion", 1, "type", "KBO_GAME_SNAPSHOT", "payload", Map.of(
                "schemaVersion", 1, "source", "fixture-kbo-pages", "mode", "game-window", "date", input.get("date"),
                "observedAt", observed.toString(), "games", List.of(input), "anomalies", List.of())));
        importer.importMessage(body);
        return body;
    }
    private long count(String table) { return jdbc.queryForObject("select count(*) from " + table, Long.class); }
    private long typeCount(String type) { return jdbc.queryForObject("select count(*) from notifications where notification_type = ?", Long.class, type); }
    private void record(long logId, int inning, boolean deleted) {
        sqlUpdate("""
                insert into inning_records(user_game_log_id,client_record_id,request_fingerprint,inning_number,caption,recorded_at,created_at,updated_at,deleted_at)
                values (?,?,?,?,'record',?,?,?,?)
                """, logId, java.util.UUID.randomUUID().toString(), "a".repeat(64), inning, NOW, NOW, NOW, deleted ? NOW : null);
    }
}
