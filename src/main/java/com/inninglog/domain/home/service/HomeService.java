package com.inninglog.domain.home.service;

import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.model.UserGameLog;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.game.service.GameUserAccess;
import com.inninglog.domain.home.dto.CalendarResponse;
import com.inninglog.domain.home.dto.WinRateResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class HomeService {
    private final GameRepository games;
    private final GameUserAccess access;
    private final Clock clock;
    public HomeService(GameRepository games, GameUserAccess access, Clock clock) {
        this.games = games; this.access = access; this.clock = clock;
    }

    public CalendarResponse calendar(String subject, int year, int month) {
        var user = access.require(subject, false);
        YearMonth requested = YearMonth.of(year, month);
        LocalDate first = requested.atDay(1), until = requested.plusMonths(1).atDay(1);
        long teamId = user.getFavoriteTeam().getId();
        Map<Long, UserGameLog> logs = games.logsForRange(user.getId(), first, until).stream()
                .collect(Collectors.toMap(UserGameLog::gameId, Function.identity()));
        Map<LocalDate, List<GameResponse>> byDate = games.forMonth(user.getId(), teamId, first, until).stream()
                .map(game -> GameResponse.from(game, logs.get(game.id()), teamId))
                .collect(Collectors.groupingBy(GameResponse::date));
        return new CalendarResponse(year, month, "Asia/Seoul", today(), teamId,
                games.syncState("MONTH:" + requested),
                first.datesUntil(until).map(date -> new CalendarResponse.Day(date, byDate.getOrDefault(date, List.of()))).toList());
    }

    public WinRateResponse winRate(String subject) {
        var user = access.require(subject, false);
        int season = today().getYear();
        Map<String, Long> counts = games.resultCounts(user.getId(), season).stream()
                .collect(Collectors.toMap(GameRepository.ResultCount::result, GameRepository.ResultCount::count));
        long wins = counts.getOrDefault("WIN", 0L), losses = counts.getOrDefault("LOSS", 0L);
        long draws = counts.getOrDefault("DRAW", 0L), pending = counts.getOrDefault("PENDING", 0L);
        long voids = counts.getOrDefault("VOID", 0L), unknown = counts.getOrDefault("UNKNOWN", 0L);
        long decided = wins + losses;
        BigDecimal rate = decided == 0 ? null : BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);
        return new WinRateResponse(season, List.of("REGULAR"), "STADIUM", wins + losses + draws + pending + voids + unknown,
                wins, losses, draws, pending, voids, unknown, games.unclassifiedCount(user.getId(), season), decided, rate, clock.instant());
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(GameUserAccess.KST)); }
}
