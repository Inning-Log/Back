package com.inninglog.domain.game.notification;

import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.model.GameStatus;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.service.NotificationLinkService;
import com.inninglog.domain.notification.service.NotificationQueueService;
import com.inninglog.domain.notification.service.PushNotification;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Runs in the importer transaction: state history, inbox and outbox succeed or roll back together. */
@Service
public class GameNotificationService {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private final JdbcTemplate jdbc;
    private final NotificationQueueService queue;
    private final NotificationLinkService links;
    private final Clock clock;
    private final Duration maxObservationAge;

    public GameNotificationService(JdbcTemplate jdbc, NotificationQueueService queue, NotificationLinkService links,
            Clock clock, @Value("${app.games.notifications.max-observation-age:PT10M}") String maxObservationAge) {
        this.jdbc = jdbc;
        this.queue = queue;
        this.links = links;
        this.clock = clock;
        this.maxObservationAge = Duration.parse(maxObservationAge);
        if (this.maxObservationAge.isNegative() || this.maxObservationAge.isZero())
            throw new IllegalArgumentException("game notification observation age must be positive");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onEffectiveState(Game game, Game previous, long stateId, String mode) {
        if (previous == null || !"game-window".equals(mode) || !"SCOREBOARD".equals(game.resultSource())
                || !game.gameDate().equals(LocalDate.now(clock.withZone(KOREA)))
                || game.resultObservedAt().isBefore(clock.instant().minus(maxObservationAge))
                || (game.status() != GameStatus.LIVE && game.status() != GameStatus.FINISHED)) return;

        List<Viewer> viewers = jdbc.query("""
                select l.id, l.user_id from user_game_logs l join app_users u on u.id = l.user_id
                 where l.game_id = ? and l.deleted_at is null and u.deleted_at is null
                """, (rs, index) -> new Viewer(rs.getLong("id"), rs.getLong("user_id")), game.id());
        if (viewers.isEmpty()) return;

        boolean previousLive = previous.status() == GameStatus.LIVE && "SCOREBOARD".equals(previous.resultSource());
        boolean adjacent = previousLive && adjacent(previous, game);
        boolean finished = previousLive && game.status() == GameStatus.FINISHED && previous.currentInning() != null
                && !previous.resultObservedAt().isBefore(clock.instant().minus(maxObservationAge))
                && (game.currentInning() == null || game.currentInning().equals(previous.currentInning()));
        boolean firstPitch = previous.status() == GameStatus.SCHEDULED && game.status() == GameStatus.LIVE
                && Objects.equals(game.currentInning(), 1) && "TOP".equals(game.currentHalf());

        if (adjacent || finished) {
            notify(viewers, game, NotificationType.GAME_INNING_ENDED,
                    "inning-ended:" + previous.currentInning() + ':' + previous.currentHalf(),
                    "이닝이 종료됐어요", inningSubject(previous.currentInning(), previous.currentHalf()) + " 종료됐어요.",
                    previous.currentInning(), previous.currentHalf());
        }
        if (adjacent || firstPitch) {
            notify(viewers, game, NotificationType.GAME_INNING_STARTED,
                    "inning-started:" + game.currentInning() + ':' + game.currentHalf(),
                    "새 이닝이 시작됐어요", inningSubject(game.currentInning(), game.currentHalf()) + " 시작됐어요.",
                    game.currentInning(), game.currentHalf());
        }
        boolean comparableScore = "SCOREBOARD".equals(previous.resultSource())
                && (previous.status() == GameStatus.LIVE || previous.status() == GameStatus.FINISHED);
        if (comparableScore && previous.homeScore() != null && previous.awayScore() != null
                && game.homeScore() != null && game.awayScore() != null
                && (!previous.homeScore().equals(game.homeScore()) || !previous.awayScore().equals(game.awayScore()))) {
            notify(viewers, game, NotificationType.GAME_SCORE_CHANGED, "score:" + stateId,
                    "경기 점수가 바뀌었어요", game.awayTeam().name() + " " + game.awayScore() + " : "
                            + game.homeScore() + " " + game.homeTeam().name(), game.currentInning(), game.currentHalf());
        }
        // A completed inning is observed, never inferred for skipped innings. Final games may end after the top half.
        if ((adjacent && "BOTTOM".equals(previous.currentHalf())) || finished) {
            int inning = previous.currentInning();
            for (Viewer viewer : viewers) {
                if (jdbc.queryForObject("""
                        select count(*) from inning_records
                         where user_game_log_id = ? and inning_number = ? and deleted_at is null
                        """, Long.class, viewer.logId(), inning) == 0) {
                    notify(List.of(viewer), game, NotificationType.RECORD_REMINDER, "reminder:" + inning,
                            "이닝의 추억을 남겨보세요", inning + "회 기록이 아직 없어요. 기억에 남는 순간을 기록해 보세요.",
                            inning, null);
                }
            }
        }
    }

    private void notify(List<Viewer> viewers, Game game, NotificationType type, String event, String title,
            String body, Integer inning, String half) {
        Map<String, String> data = new HashMap<>();
        data.put("gameId", String.valueOf(game.id()));
        data.put("screen", "GAME");
        data.put("gameDate", game.gameDate().toString());
        data.put("observedAt", game.resultObservedAt().toString());
        if (inning != null) data.put("inning", inning.toString());
        if (half != null) data.put("half", half);
        String link = links.game(game.id());
        if (link != null) data.put("link", link);
        for (Viewer viewer : viewers) {
            Map<String, String> personalData = new HashMap<>(data);
            personalData.put("userGameLogId", Long.toString(viewer.logId()));
            queue.enqueueToUser("game:" + game.id() + ':' + event, viewer.userId(), new PushNotification(type, title, body, personalData));
        }
    }

    private static boolean adjacent(Game previous, Game next) {
        if (next.status() != GameStatus.LIVE || previous.currentInning() == null || next.currentInning() == null) return false;
        return (previous.currentInning().equals(next.currentInning()) && "TOP".equals(previous.currentHalf()) && "BOTTOM".equals(next.currentHalf()))
                || (next.currentInning() == previous.currentInning() + 1 && "BOTTOM".equals(previous.currentHalf()) && "TOP".equals(next.currentHalf()));
    }

    private static String inningSubject(int inning, String half) {
        return inning + "회" + ("TOP".equals(half) ? "초가" : "말이");
    }
    private record Viewer(long logId, long userId) {}
}
