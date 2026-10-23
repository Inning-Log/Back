package com.inninglog.domain.notification.service;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Retains the inbox/idempotency ledger; only terminal delivery details are removed. */
@Service
public class NotificationRetentionService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int days;

    public NotificationRetentionService(JdbcTemplate jdbc, Clock clock,
            @Value("${app.notifications.retention-days:30}") int days) {
        if (days < 1) throw new IllegalArgumentException("Notification retention must be at least one day.");
        this.jdbc = jdbc;
        this.clock = clock;
        this.days = days;
    }

    @Scheduled(fixedDelayString = "${app.notifications.cleanup-delay:PT1H}",
            initialDelayString = "${app.notifications.cleanup-initial-delay:PT5M}")
    @Transactional
    public int cleanCompleted() {
        return jdbc.update("""
                delete from notification_outbox where id in (
                  select o.id from notification_outbox o
                  where o.status in ('COMPLETED', 'COMPLETED_WITH_FAILURES') and o.completed_at < ?
                    and not exists (select 1 from notification_delivery_targets t where t.outbox_id = o.id
                                    and t.status in ('PENDING', 'PROCESSING', 'RETRY'))
                  order by o.id limit 500
                )
                """, java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(days))));
    }
}
