package com.inninglog.domain.home.service;

import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.model.GameRecordingSummary;
import com.inninglog.domain.game.model.GameType;
import com.inninglog.domain.home.dto.PostseasonWinRate;
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
    private static final List<GameType> POSTSEASON_TYPES = List.of(GameType.WILDCARD, GameType.SEMI_PLAYOFF,
            GameType.PLAYOFF, GameType.KOREAN_SERIES);
    private static final List<GameType> WIN_RATE_TYPES = java.util.stream.Stream.concat(
            java.util.stream.Stream.of(GameType.REGULAR), POSTSEASON_TYPES.stream()).toList();
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
        var recordings = games.recordingSummariesForRange(user.getId(), first, until);
        Map<LocalDate, List<GameResponse>> byDate = games.forMonth(user.getId(), teamId, first, until).stream()
                .map(game -> GameResponse.from(game, logs.get(game.id()), teamId,
                        recordings.getOrDefault(game.id(), GameRecordingSummary.EMPTY)))
                .collect(Collectors.groupingBy(GameResponse::date));
        return new CalendarResponse(year, month, "Asia/Seoul", today(), teamId,
                games.syncState("MONTH:" + requested),
                first.datesUntil(until).map(date -> new CalendarResponse.Day(date, byDate.getOrDefault(date, List.of()))).toList());
    }

    public WinRateResponse winRate(String subject, Integer year) {
        var user = access.require(subject, false);
        int season = year == null ? today().getYear() : year;
        Map<String, Long> counts = games.resultCounts(user.getId(), season, WIN_RATE_TYPES).stream()
                .collect(Collectors.toMap(GameRepository.ResultCount::result, GameRepository.ResultCount::count));
        long wins = counts.getOrDefault("WIN", 0L), losses = counts.getOrDefault("LOSS", 0L);
        long draws = counts.getOrDefault("DRAW", 0L), pending = counts.getOrDefault("PENDING", 0L);
        long voids = counts.getOrDefault("VOID", 0L), unknown = counts.getOrDefault("UNKNOWN", 0L);
        long decided = wins + losses;
        BigDecimal rate = decided == 0 ? null : BigDecimal.valueOf(wins).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(decided), 1, RoundingMode.HALF_UP);
        return new WinRateResponse(season, WIN_RATE_TYPES.stream().map(Enum::name).toList(), "STADIUM", wins + losses + draws + pending + voids + unknown,
                wins, losses, draws, pending, voids, unknown, games.unclassifiedCount(user.getId(), season), decided, rate, clock.instant(),
                PostseasonWinRate.from(POSTSEASON_TYPES.stream().map(Enum::name).toList(),
                        games.resultCounts(user.getId(), season, POSTSEASON_TYPES)));
    }

    private LocalDate today() { return LocalDate.now(clock.withZone(GameUserAccess.KST)); }
}
