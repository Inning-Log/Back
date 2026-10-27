package com.inninglog.domain.timeline.service;

import com.inninglog.domain.game.model.GameStatus;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.game.service.GameUserAccess;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import org.springframework.stereotype.Service;

/** One league-wide boundary for every team, in Korea time. No stored media is reset. */
@Service
public class TimelineDayPolicy {
    private static final Set<GameStatus> EXCLUDED = Set.of(GameStatus.CANCELED, GameStatus.POSTPONED);
    private final GameRepository games;
    private final Clock clock;

    public TimelineDayPolicy(GameRepository games, Clock clock) { this.games = games; this.clock = clock; }

    public Instant boundary(LocalDate date) {
        var known = games.forDate(date).stream().filter(g -> !EXCLUDED.contains(g.status()))
                .map(g -> g.scheduledAt()).filter(java.util.Objects::nonNull)
                .min(Instant::compareTo);
        return java.util.stream.Stream.concat(known.stream(), games.earliestUnconfirmedStart(date).stream())
                .min(Instant::compareTo)
                .orElse(date.atTime(LocalTime.of(18, 30)).atZone(GameUserAccess.KST).toInstant());
    }

    public Window current() {
        Instant now = clock.instant();
        LocalDate today = now.atZone(GameUserAccess.KST).toLocalDate();
        Instant reset = boundary(today);
        return now.isBefore(reset) ? new Window(today.minusDays(1), reset)
                : new Window(today, boundary(today.plusDays(1)));
    }

    public boolean recordableDate(LocalDate date) {
        LocalDate today = clock.instant().atZone(GameUserAccess.KST).toLocalDate();
        return date.equals(today) || (date.equals(today.minusDays(1)) && clock.instant().isBefore(boundary(today)));
    }

    public record Window(LocalDate timelineDate, Instant nextResetAt) {}
}
