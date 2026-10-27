package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.inninglog.domain.game.model.*;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.timeline.service.TimelineDayPolicy;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimelineDayPolicyTest {
    final GameRepository games = mock(GameRepository.class);
    final LocalDate date = LocalDate.of(2026,10,27);
    Game game(String time, GameStatus status) {
        return new Game(1,2026,GameType.PLAYOFF,date,null,Instant.parse("2026-10-27T"+time+"Z"),null,null,
                null,null,null,null,null,status,null,null,null,null,null);
    }
    TimelineDayPolicy policy(String now) { return new TimelineDayPolicy(games,Clock.fixed(Instant.parse(now),ZoneOffset.UTC)); }

    @Test void earliestLeagueGameResetsEveryTeamAtExactBoundary() {
        when(games.forDate(date)).thenReturn(List.of(game("09:30:00",GameStatus.SCHEDULED),game("05:00:00",GameStatus.SCHEDULED)));
        var before = policy("2026-10-27T04:59:59Z");
        assertThat(before.current().timelineDate()).isEqualTo(date.minusDays(1));
        assertThat(before.current().nextResetAt()).isEqualTo(Instant.parse("2026-10-27T05:00:00Z"));
        assertThat(before.recordableDate(date.minusDays(1))).isTrue();
        var at = policy("2026-10-27T05:00:00Z");
        assertThat(at.current().timelineDate()).isEqualTo(date);
        assertThat(at.recordableDate(date.minusDays(1))).isFalse();
    }
    @Test void allTeamsOffOrCanceledUses1830AndKeepsYesterdayAfterMidnight() {
        when(games.forDate(date)).thenReturn(List.of(game("05:00:00",GameStatus.CANCELED),game("08:00:00",GameStatus.POSTPONED)));
        var before = policy("2026-10-26T15:00:00Z");
        assertThat(before.current().timelineDate()).isEqualTo(date.minusDays(1));
        assertThat(before.current().nextResetAt()).isEqualTo(Instant.parse("2026-10-27T09:30:00Z"));
        assertThat(policy("2026-10-27T09:30:00Z").current().timelineDate()).isEqualTo(date);
    }
    @Test void delayedGameUsesPublishedStartAndMonthYearBoundaryWorks() {
        when(games.forDate(date)).thenReturn(List.of(game("10:00:00",GameStatus.DELAYED)));
        assertThat(policy("2026-10-27T09:30:00Z").current().nextResetAt()).isEqualTo(Instant.parse("2026-10-27T10:00:00Z"));
        assertThat(policy("2026-12-31T15:00:00Z").current().timelineDate()).isEqualTo(LocalDate.of(2026,12,31));
    }
}
