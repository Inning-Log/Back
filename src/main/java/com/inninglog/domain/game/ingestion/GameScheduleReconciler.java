package com.inninglog.domain.game.ingestion;

import static com.inninglog.domain.game.repository.GameRepository.timestamp;
import com.inninglog.domain.game.model.GameType;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Called under game_import_lock. Absence is withdrawal of a fixture, never a fabricated cancellation. */
@Component
public class GameScheduleReconciler {
    private final JdbcTemplate jdbc;
    public GameScheduleReconciler(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Coverage> coverage(JsonNode payload, Instant envelopeTime) {
        JsonNode coverage = payload.path("scheduleCoverage");
        if (coverage.isMissingNode() || coverage.isNull()) return List.of();
        if (!coverage.isArray() || coverage.size() > 6) throw new GameImportException("INVALID_SCHEDULE_COVERAGE");
        var result = new ArrayList<Coverage>();
        var types = new HashSet<GameType>();
        for (var item : coverage) {
            try {
                GameType type = GameType.valueOf(item.path("gameType").asText());
                Instant observedAt = Instant.parse(item.path("observedAt").asText());
                if (type == GameType.UNKNOWN || !types.add(type) || observedAt.isAfter(envelopeTime)) throw new IllegalArgumentException();
                result.add(new Coverage(type, observedAt));
            } catch (RuntimeException invalid) { throw new GameImportException("INVALID_SCHEDULE_COVERAGE"); }
        }
        return result;
    }

    public void seen(long id, GameSnapshotImporter.Incoming value) {
        var covered = jdbc.queryForList("select observed_at from game_schedule_coverage where month_key = ? and game_type = ?",
                java.time.OffsetDateTime.class, YearMonth.from(value.date()).toString(), value.type().name());
        // A late old SQS message must not resurrect a withdrawn fixture, including a previously unseen one.
        if (!covered.isEmpty() && value.scheduleObservedAt().isBefore(covered.getFirst().toInstant())) {
            jdbc.update("""
                    update games set schedule_active = false, schedule_membership_observed_at = ?
                     where id = ? and schedule_membership_observed_at is null and status in ('SCHEDULED','UNKNOWN','DELAYED','CANCELED','POSTPONED')
                    """, covered.getFirst(), id);
            return;
        }
        jdbc.update("""
                update games set schedule_active = true, schedule_membership_observed_at = ?
                 where id = ? and (schedule_membership_observed_at is null or schedule_membership_observed_at < ?)
                """, timestamp(value.scheduleObservedAt()), id, timestamp(value.scheduleObservedAt()));
    }

    public void reconcile(YearMonth month, List<Coverage> coverage, Set<Long> matched, JsonNode deferred) {
        var starts = new HashMap<String, DeferredStart>();
        if (!deferred.isMissingNode() && !deferred.isArray()) throw new GameImportException("INVALID_DEFERRED_GAMES");
        if (deferred.isArray()) for (var row : deferred) {
            try {
                var date = java.time.LocalDate.parse(row.path("date").asText());
                var type = GameType.valueOf(row.path("gameType").asText());
                var time = java.time.OffsetDateTime.parse(row.path("scheduledAt").asText()).toInstant();
                if (!YearMonth.from(date).equals(month)
                        || !time.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().equals(date)) throw new IllegalArgumentException();
                var slot = new DeferredStart(date, type, time);
                starts.merge(date+":"+type, slot, (a,b) -> a.time().isBefore(b.time()) ? a : b);
            } catch (RuntimeException invalid) { throw new GameImportException("INVALID_DEFERRED_GAMES"); }
        }
        for (Coverage item : coverage) {
            var previous = jdbc.queryForList("select observed_at from game_schedule_coverage where month_key = ? and game_type = ?",
                    java.time.OffsetDateTime.class, month.toString(), item.type().name());
            if (!previous.isEmpty() && !item.observedAt().isAfter(previous.getFirst().toInstant())) continue;
            jdbc.update("delete from game_unconfirmed_starts where game_date >= ? and game_date < ? and game_type = ?",
                    month.atDay(1), month.plusMonths(1).atDay(1), item.type().name());
            for (var slot : starts.values()) if (slot.type() == item.type())
                jdbc.update("insert into game_unconfirmed_starts(game_date,game_type,scheduled_at) values (?,?,?)",
                        slot.date(), slot.type().name(), timestamp(slot.time()));
            var candidates = jdbc.queryForList("""
                    select id from games where game_date >= ? and game_date < ? and game_type = ?
                      and status in ('SCHEDULED','UNKNOWN','DELAYED','CANCELED','POSTPONED')
                      and schedule_observed_at <= ? and result_observed_at <= ?
                      and (schedule_membership_observed_at is null or schedule_membership_observed_at <= ?)
                    """, Long.class, month.atDay(1), month.plusMonths(1).atDay(1), item.type().name(),
                    timestamp(item.observedAt()), timestamp(item.observedAt()), timestamp(item.observedAt()));
            for (long id : candidates) if (!matched.contains(id)) {
                jdbc.update("update games set schedule_active = false, schedule_membership_observed_at = ? where id = ?",
                        timestamp(item.observedAt()), id);
            }
            if (previous.isEmpty()) jdbc.update("insert into game_schedule_coverage(month_key,game_type,observed_at) values (?,?,?)",
                    month.toString(), item.type().name(), timestamp(item.observedAt()));
            else jdbc.update("update game_schedule_coverage set observed_at = ? where month_key = ? and game_type = ?",
                    timestamp(item.observedAt()), month.toString(), item.type().name());
        }
    }
    public record Coverage(GameType type, Instant observedAt) {}
    private record DeferredStart(java.time.LocalDate date, GameType type, Instant time) {}
}
