package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.home.service.HomeService;
import com.inninglog.domain.user.entity.User;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class HomeSeasonTest {
    @Test
    void seasonChangesAtKstMidnightEvenWhenUtcIsStillDecember31() {
        var repository = mock(GameRepository.class);
        var access = mock(GameUserAccess.class);
        var user = mock(User.class);
        when(access.require("1",false)).thenReturn(user);
        when(user.getId()).thenReturn(1L);
        when(repository.resultCounts(1,2026)).thenReturn(List.of(new GameRepository.ResultCount("WIN",3)));
        when(repository.resultCounts(1,2027)).thenReturn(List.of());
        var before = new HomeService(repository,access,Clock.fixed(Instant.parse("2026-12-31T14:59:59Z"),ZoneOffset.UTC));
        var after = new HomeService(repository,access,Clock.fixed(Instant.parse("2026-12-31T15:00:00Z"),ZoneOffset.UTC));
        assertThat(before.winRate("1").seasonYear()).isEqualTo(2026);
        assertThat(before.winRate("1").wins()).isEqualTo(3);
        assertThat(after.winRate("1").seasonYear()).isEqualTo(2027);
        assertThat(after.winRate("1").winRatePercent()).isNull();
    }
}
