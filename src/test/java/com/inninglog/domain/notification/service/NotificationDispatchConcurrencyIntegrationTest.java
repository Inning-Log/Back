package com.inninglog.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.inninglog.domain.notification.dto.PushRegistrationRequest;
import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.entity.NotificationType;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=${DISPATCH_TEST_JDBC_URL:jdbc:h2:mem:notification_dispatch_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${GAME_TEST_DB_USER:sa}", "spring.datasource.password=${GAME_TEST_DB_PASSWORD:}",
        "app.firebase.dispatch.batch-size=1", "app.firebase.dispatch.claim-lease=30s"
})
@ActiveProfiles("test")
@Import(NotificationDispatchConcurrencyIntegrationTest.GatewayConfiguration.class)
class NotificationDispatchConcurrencyIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired PushRegistrationService registrations;
    @Autowired NotificationQueueService queue;
    @Autowired NotificationDispatchService dispatcher;
    @Autowired TransactionTemplate transactions;
    @Autowired ParallelGateway gateway;
    @Autowired Clock clock;

    @Test
    void fourWorkersDeliverEachTargetOnceAndCompleteExpiredPendingAndRetryEvents() throws Exception {
        User user = users.saveAndFlush(new User("parallel-" + UUID.randomUUID() + "@example.com", null));
        for (int installation = 0; installation < 4; installation++) {
            registrations.register(user.getId().toString(), new PushRegistrationRequest(DevicePlatform.WEB,
                    "parallel-fid-" + UUID.randomUUID()));
        }
        long expiredRetry = enqueue(user, "expired-expanded");
        assertThat(dispatcher.dispatchNext()).isTrue(); // Materialize four delivery targets.
        Timestamp past = Timestamp.from(clock.instant().minus(Duration.ofDays(8)));
        jdbc.update("update notification_outbox set created_at = ? where notification_id = ?", past, expiredRetry);
        jdbc.update("update notification_delivery_targets set status = 'RETRY', next_attempt_at = ?", past);

        long expiredPending = enqueue(user, "expired-pending");
        jdbc.update("update notification_outbox set created_at = ? where notification_id = ?", past, expiredPending);
        for (int event = 0; event < 3; event++) enqueue(user, "active-" + event);

        CountDownLatch start = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(4, Thread.ofPlatform().daemon().factory());
        boolean terminated;
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(workers.submit(() -> {
                    await(start);
                    for (int attempt = 0; attempt < 100; attempt++) {
                        if (!dispatcher.dispatchNext()) {
                            // Another worker may still be committing newly expanded targets.
                            if (jdbc.queryForObject("select count(*) from notification_outbox where status in ('PENDING','PROCESSING')", Long.class) == 0)
                                return;
                            try { Thread.sleep(10); }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                        }
                    }
                    throw new AssertionError("Bounded queue did not drain after 100 attempts");
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
            terminated = workers.awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(terminated).isTrue();
        assertThat(gateway.observed.size()).isEqualTo(12);
        assertThat(gateway.observed.values()).allSatisfy(count -> assertThat(count.get()).isEqualTo(1));
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets where status = 'SENT'", Long.class)).isEqualTo(12);
        assertThat(jdbc.queryForObject("select count(*) from notification_delivery_targets where status <> 'SENT'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from notification_outbox where status = 'COMPLETED'", Long.class)).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from notifications", Long.class)).isEqualTo(5);
        assertThat(dispatcher.dispatchNext()).isFalse();
        assertThat(gateway.observed.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(12);
    }

    private long enqueue(User user, String key) {
        return transactions.execute(ignored -> queue.enqueueToUser(key, user.getId(),
                new PushNotification(NotificationType.FRIEND_ACCEPTED, "친구 알림", "테스트", Map.of())));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Concurrent workers did not reach the barrier");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean @Primary ParallelGateway parallelGateway() { return new ParallelGateway(); }
    }

    static class ParallelGateway implements PushGateway {
        final ConcurrentHashMap<Long, AtomicInteger> observed = new ConcurrentHashMap<>();
        final CountDownLatch firstConcurrentClaims = new CountDownLatch(4);

        @Override
        public PushBatchResult send(PushNotification notification, List<PushTarget> targets) {
            targets.forEach(target -> observed.computeIfAbsent(target.deliveryTargetId(), ignored -> new AtomicInteger()).incrementAndGet());
            firstConcurrentClaims.countDown();
            await(firstConcurrentClaims); // Forces four claims to overlap before any can complete.
            return new PushBatchResult(targets.stream().map(PushTargetResult::success).toList());
        }
    }
}
