package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;

import com.inninglog.domain.notification.dto.PushRegistrationRequest;
import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.notification.service.*;
import com.inninglog.domain.user.entity.User;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=${LIFECYCLE_TEST_JDBC_URL:jdbc:h2:mem:notification_lifecycle;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${GAME_TEST_DB_USER:sa}", "spring.datasource.password=${GAME_TEST_DB_PASSWORD:}", "app.games.allow-fixtures=true"
})
@Import(NotificationLifecycleIntegrationTest.GatewayConfiguration.class)
class NotificationLifecycleIntegrationTest extends GameIntegrationSupport {
    @Autowired NotificationQueueService queue;
    @Autowired NotificationDispatchService dispatcher;
    @Autowired NotificationRetentionService retention;
    @Autowired PushRegistrationService registrations;
    @Autowired TransactionTemplate transactions;
    @Autowired TestGateway gateway;

    @BeforeEach
    void resetFixture() {
        // This class uses a dedicated database, independent of the game and queue suites.
        for (String table : List.of("notification_delivery_targets", "notification_outbox", "notifications",
                "video_comments", "inning_record_videos", "inning_records", "user_game_logs", "game_external_ids",
                "game_state_snapshots", "game_snapshot_receipts", "games", "friendships")) {
            sqlUpdate("delete from " + table);
        }
        gateway.calls = 0;
        gateway.retry = false;
        gateway.last = null;
    }

    @Test
    void expiredPendingEventCompletesWithoutGatewayAndPreservesInbox() {
        User user = registered();
        long notification = enqueue("expired", user, NotificationType.GAME_SCORE_CHANGED, Map.of());
        sqlUpdate("update notification_outbox set created_at = ? where notification_id = ?", NOW.minusSeconds(600), notification);
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(gateway.calls).isZero();
        assertThat(status(notification)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from notifications where id = ?", Long.class, notification)).isEqualTo(1);
    }

    @Test
    void expirationIsRecheckedOnRetryAndAbsoluteExpiryIsPassedToGateway() {
        User user = registered();
        long notification = enqueue("retry-expired", user, NotificationType.GAME_SCORE_CHANGED, Map.of());
        failFirstAttempt();
        assertThat(gateway.last.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(gateway.last.withSystemData(Map.of("sample", "value")).expiresAt()).isEqualTo(gateway.last.expiresAt());
        sqlUpdate("update notification_outbox set created_at = ? where notification_id = ?", NOW.minusSeconds(601), notification);
        readyRetry();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(notification)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets", Long.class)).isZero();
    }

    @Test
    void acceptedFriendRequestIsCancelledBeforeRetry() {
        User requester = user(), receiver = registered();
        long friendship = friendship(requester, receiver, false);
        long notification = enqueue("friendship:" + friendship + ":requested:1", receiver, NotificationType.FRIEND_REQUEST,
                Map.of("friendshipId", Long.toString(friendship), "requestRevision", "1"));
        failFirstAttempt();
        sqlUpdate("update friendships set status = 'ACCEPTED', responded_at = ? where id = ?", NOW, friendship);
        readyRetry();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(notification)).isEqualTo("COMPLETED");
    }

    @Test
    void removedViewingIsCancelledBeforeExpansionAndNewRecordCancelsReminderRetry() {
        User user = registered();
        long game = ingest(game(TODAY, "LIVE", 0, 0));
        long log = viewing(user, game, team("LG"), "HOME");
        Map<String, String> data = Map.of("gameId", Long.toString(game), "userGameLogId", Long.toString(log), "inning", "3");
        long removed = enqueue("removed-viewing", user, NotificationType.GAME_INNING_STARTED, data);
        sqlUpdate("update user_game_logs set deleted_at = ? where id = ?", NOW, log);
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isZero();
        assertThat(status(removed)).isEqualTo("COMPLETED");

        sqlUpdate("update user_game_logs set deleted_at = null where id = ?", log);
        long reminder = enqueue("recorded-reminder", user, NotificationType.RECORD_REMINDER, data);
        failFirstAttempt();
        assertThat(gateway.last.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        record(log, 3);
        readyRetry();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(reminder)).isEqualTo("COMPLETED");
    }

    @Test
    void cancelledGameSuppressesPendingProgressAndPostponedGameSuppressesReminderRetry() {
        User user = registered();
        long game = ingest(game(TODAY, "LIVE", 0, 0));
        long log = viewing(user, game, team("LG"), "HOME");
        Map<String, String> data = Map.of("gameId", Long.toString(game), "userGameLogId", Long.toString(log), "inning", "3");
        long progress = enqueue("cancelled-game", user, NotificationType.GAME_INNING_STARTED, data);
        sqlUpdate("update games set status = 'CANCELED' where id = ?", game);
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isZero();
        assertThat(status(progress)).isEqualTo("COMPLETED");

        sqlUpdate("update games set status = 'LIVE' where id = ?", game);
        long reminder = enqueue("postponed-reminder", user, NotificationType.RECORD_REMINDER, data);
        failFirstAttempt();
        sqlUpdate("update games set status = 'POSTPONED' where id = ?", game);
        readyRetry();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(reminder)).isEqualTo("COMPLETED");
    }

    @Test
    void deletedCommentCancelsRetryAndUnfriendingCancelsPendingComment() {
        User owner = registered(), author = user();
        long game = ingest(game(TODAY, "LIVE", 0, 0));
        long log = viewing(owner, game, team("LG"), "HOME");
        long record = record(log, 3);
        long friendship = friendship(author, owner, true);
        sqlUpdate("""
                insert into inning_record_videos(inning_record_id,object_key,content_type,size_bytes,status,created_at,updated_at)
                values (?,?,'video/mp4',128,'READY',?,?)
                """, record, UUID.randomUUID().toString(), NOW, NOW);
        long video = jdbc.queryForObject("select max(id) from inning_record_videos", Long.class);
        sqlUpdate("""
                insert into video_comments(video_id,author_user_id,client_comment_id,request_fingerprint,content,created_at,updated_at)
                values (?,?,?,?,'comment',?,?)
                """, video, author.getId(), UUID.randomUUID().toString(), "a".repeat(64), NOW, NOW);
        long comment = jdbc.queryForObject("select max(id) from video_comments", Long.class);
        Map<String, String> data = Map.of("commentId", Long.toString(comment), "videoId", Long.toString(video));
        long deleted = enqueue("deleted-comment", owner, NotificationType.TIMELINE_COMMENT, data);
        failFirstAttempt();
        assertThat(gateway.last.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        sqlUpdate("update video_comments set deleted_at = ? where id = ?", NOW, comment);
        readyRetry();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(deleted)).isEqualTo("COMPLETED");

        sqlUpdate("update video_comments set deleted_at = null where id = ?", comment);
        long unfriended = enqueue("unfriended-comment", owner, NotificationType.TIMELINE_COMMENT, data);
        sqlUpdate("delete from friendships where id = ?", friendship);
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(status(unfriended)).isEqualTo("COMPLETED");
    }

    @Test
    void retentionDeletesOnlyOldTerminalDetailsAndInboxPreventsReenqueue() {
        User user = registered();
        long completed = enqueue("retained-event", user, NotificationType.FRIEND_ACCEPTED, Map.of());
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(status(completed)).isEqualTo("COMPLETED");
        sqlUpdate("update notification_outbox set completed_at = ? where notification_id = ?", NOW.minus(Duration.ofDays(31)), completed);

        long active = enqueue("active-retry", user, NotificationType.FRIEND_ACCEPTED, Map.of());
        gateway.retry = true;
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(dispatcher.dispatchNext()).isTrue();
        sqlUpdate("update notification_outbox set created_at = ? where notification_id = ?", NOW.minus(Duration.ofDays(31)), active);
        long pending = enqueue("active-pending", user, NotificationType.FRIEND_ACCEPTED, Map.of());
        sqlUpdate("update notification_outbox set created_at = ? where notification_id = ?", NOW.minus(Duration.ofDays(31)), pending);
        long recent = enqueue("recent-completed", user, NotificationType.FRIEND_ACCEPTED, Map.of());
        sqlUpdate("update notification_outbox set status = 'COMPLETED', completed_at = ? where notification_id = ?", NOW.minus(Duration.ofDays(1)), recent);

        assertThat(retention.cleanCompleted()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where notification_id = ?", Long.class, completed)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets", Long.class)).isEqualTo(1);
        assertThat(status(active)).isEqualTo("PROCESSING");
        assertThat(status(pending)).isEqualTo("PENDING");
        assertThat(status(recent)).isEqualTo("COMPLETED");
        assertThat(enqueue("retained-event", user, NotificationType.FRIEND_ACCEPTED, Map.of())).isEqualTo(completed);
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where notification_id = ?", Long.class, completed)).isZero();

        // Defensive preservation even when a terminal header incorrectly still has an active target.
        sqlUpdate("update notification_outbox set status = 'COMPLETED_WITH_FAILURES', completed_at = ? where notification_id = ?", NOW.minus(Duration.ofDays(31)), active);
        assertThat(retention.cleanCompleted()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets", Long.class)).isEqualTo(1);
    }

    private User registered() {
        User user = user();
        registrations.register(user.getId().toString(), new PushRegistrationRequest(DevicePlatform.WEB, "fid-" + UUID.randomUUID()));
        return user;
    }

    private long enqueue(String key, User user, NotificationType type, Map<String, String> data) {
        return transactions.execute(ignored -> queue.enqueueToUser(key, user.getId(), new PushNotification(type, "알림", "내용", data)));
    }

    private void failFirstAttempt() {
        gateway.retry = true;
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(dispatcher.dispatchNext()).isTrue();
        assertThat(gateway.calls).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets where status = 'RETRY'", Long.class)).isEqualTo(1);
    }

    private void readyRetry() { sqlUpdate("update notification_delivery_targets set next_attempt_at = ? where status = 'RETRY'", NOW); }
    private String status(long notification) { return jdbc.queryForObject("select status from notification_outbox where notification_id = ?", String.class, notification); }

    private long friendship(User requester, User receiver, boolean accepted) {
        sqlUpdate("""
                insert into friendships(requester_id,receiver_id,pair_key,status,requested_at,responded_at,created_at,updated_at)
                values (?,?,?,?,?,?,?,?)
                """, requester.getId(), receiver.getId(), Math.min(requester.getId(), receiver.getId()) + ":" + Math.max(requester.getId(), receiver.getId()),
                accepted ? "ACCEPTED" : "PENDING", NOW, accepted ? NOW : null, NOW, NOW);
        return jdbc.queryForObject("select max(id) from friendships", Long.class);
    }

    private long record(long log, int inning) {
        sqlUpdate("""
                insert into inning_records(user_game_log_id,client_record_id,request_fingerprint,inning_number,caption,recorded_at,created_at,updated_at)
                values (?,?,?,?,'record',?,?,?)
                """, log, UUID.randomUUID().toString(), "a".repeat(64), inning, NOW, NOW, NOW);
        return jdbc.queryForObject("select max(id) from inning_records", Long.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean @Primary TestGateway lifecycleGateway() { return new TestGateway(); }
    }

    static class TestGateway implements PushGateway {
        int calls;
        boolean retry;
        PushNotification last;

        @Override
        public PushBatchResult send(PushNotification notification, List<PushTarget> targets) {
            calls++;
            last = notification;
            return new PushBatchResult(targets.stream().map(target -> retry
                    ? PushTargetResult.failure(target, PushTargetOutcome.RETRYABLE_FAILURE, "UNAVAILABLE")
                    : PushTargetResult.success(target)).toList());
        }
    }
}
