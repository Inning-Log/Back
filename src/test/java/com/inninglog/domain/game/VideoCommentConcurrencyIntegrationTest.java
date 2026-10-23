package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.inninglog.domain.comment.dto.CommentCreateRequest;
import com.inninglog.domain.comment.repository.VideoCommentRepository;
import com.inninglog.domain.comment.service.VideoCommentService;
import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.friendship.service.FriendshipService;
import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.video.repository.VideoRepository;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

// Deliberately committed fixtures: each worker must see the rows in its own real transaction.
// A separate H2 database avoids deleting fixtures belonging to any other integration test.
@Import(VideoCommentConcurrencyIntegrationTest.GateConfiguration.class)
class VideoCommentConcurrencyIntegrationTest extends GameIntegrationSupport {
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger();
    @Autowired VideoCommentService comments;
    @Autowired VideoRepository videos;
    @Autowired FriendshipRepository friendships;
    @Autowired FriendshipService friendshipService;
    @Autowired GatedCommentRepository repository;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        if (System.getenv("VIDEO_COMMENT_TEST_JDBC_URL") != null)
            registry.add("spring.datasource.url", () -> System.getenv("VIDEO_COMMENT_TEST_JDBC_URL"));
        else if (System.getenv("GAME_TEST_JDBC_URL") == null)
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:video_comment_concurrency;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
    }

    @TestConfiguration
    static class GateConfiguration {
        @Bean @Primary GatedCommentRepository gatedCommentRepository(JdbcTemplate jdbc) {
            return new GatedCommentRepository(jdbc);
        }
    }

    static class GatedCommentRepository extends VideoCommentRepository {
        private volatile Gate gate;
        GatedCommentRepository(JdbcTemplate jdbc) { super(jdbc); }

        // This repository is proxied for persistence exception translation. Invoke a method so the
        // gate is stored on the actual target, not on the uninitialized CGLIB proxy instance.
        public void setGate(Gate gate) { this.gate = gate; }

        @Override
        public void lockParents(long viewerId, Video video, long parentAuthorId) {
            Gate current = gate;
            if (current != null && current.videoId == video.id()) {
                // Pause after the first visibility check, before the real production row locks.
                current.visibilityChecked.countDown();
                await(current.continueLocking);
            }
            super.lockParents(viewerId, video, parentAuthorId);
        }
    }

    static class Gate {
        final long videoId;
        final CountDownLatch visibilityChecked = new CountDownLatch(1);
        final CountDownLatch continueLocking = new CountDownLatch(1);
        Gate(long videoId) { this.videoId = videoId; }
    }

    @Test
    void concurrentRetryCreatesOneCommentAndOneInboxAndOutbox() throws Exception {
        var fixture = fixture();
        var request = new CommentCreateRequest(UUID.randomUUID(), "동시에 도착한 같은 댓글");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                ready.countDown(); await(start);
                return comments.create(fixture.friend().getId().toString(), fixture.videoId(), request);
            });
            var second = workers.submit(() -> {
                ready.countDown(); await(start);
                return comments.create(fixture.friend().getId().toString(), fixture.videoId(), request);
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var one = first.get(15, TimeUnit.SECONDS);
                var two = second.get(15, TimeUnit.SECONDS);
                assertThat(one.comment().id()).isEqualTo(two.comment().id());
                assertThat(one.created()).isNotEqualTo(two.created());
                assertThat(count("video_comments", "video_id", fixture.videoId())).isEqualTo(1);
                assertThat(count("notifications", "user_id", fixture.owner().getId())).isEqualTo(1);
                assertThat(count("notification_outbox", "user_id", fixture.owner().getId())).isEqualTo(1);
            } finally {
                start.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void unfriendCommittedDuringCommentCreationPreventsCommentAndNotification() throws Exception {
        var fixture = fixture();
        var gate = new Gate(fixture.videoId());
        repository.setGate(gate);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                try {
                    comments.create(fixture.friend().getId().toString(), fixture.videoId(),
                            new CommentCreateRequest(UUID.randomUUID(), "친구 해제와 경합하는 댓글"));
                    return "CREATED";
                } catch (GameException denied) {
                    return denied.code();
                }
            });
            try {
                assertThat(gate.visibilityChecked.await(10, TimeUnit.SECONDS)).isTrue();
                // Runs the actual friendship service and commits while the comment transaction is open.
                friendshipService.delete(fixture.owner().getId().toString(), fixture.friendshipId());
                gate.continueLocking.countDown();
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("VIDEO_NOT_FOUND");
                assertThat(count("video_comments", "video_id", fixture.videoId())).isZero();
                assertThat(count("notifications", "user_id", fixture.owner().getId())).isZero();
                assertThat(count("notification_outbox", "user_id", fixture.owner().getId())).isZero();
            } finally {
                gate.continueLocking.countDown();
                repository.setGate(null);
                worker.shutdownNow();
            }
        }
    }

    private Fixture fixture() throws Exception {
        var owner = user();
        var friend = user();
        var relationship = Friendship.request(owner, friend, NOW);
        relationship.accept(NOW);
        long friendshipId = friendships.saveAndFlush(relationship).getId();
        var snapshot = game(TODAY, "FINISHED", 5, 3);
        // Sequence is limited to 0..2; vary the opponent as well for committed fixtures.
        int fixtureNumber = FIXTURE_SEQUENCE.getAndIncrement();
        snapshot.put("gameSequence", fixtureNumber % 2 + 1);
        snapshot.put("awayTeam", Map.of("code", new String[]{"DOO", "KT", "SSG"}[fixtureNumber / 2]));
        long gameId = ingest(snapshot);
        viewing(owner, gameId, team("LG"), "STADIUM");
        var response = mvc.perform(post("/api/inning-records").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("gameId", gameId, "clientRecordId", UUID.randomUUID().toString(),
                                "inning", 1, "half", "TOP", "recordedAt", NOW.minusSeconds(30).toString(), "text", "동시성 검증"))))
                .andExpect(status().isCreated()).andReturn();
        long recordId = mapper.readTree(response.getResponse().getContentAsString()).path("id").longValue();
        long videoId = videos.insertPending(recordId, "test/" + UUID.randomUUID(), "video/mp4", 10, NOW);
        videos.markReady(videoId, NOW);
        return new Fixture(owner, friend, friendshipId, videoId);
    }

    @Test
    void concurrentReplyRetryCreatesOneReplyAndNotifiesParentAuthorOnce() throws Exception {
        var fixture = fixture();
        long parent = comments.create(fixture.friend().getId().toString(), fixture.videoId(),
                new CommentCreateRequest(UUID.randomUUID(), "원댓글")).comment().id();
        var request = new CommentCreateRequest(UUID.randomUUID(), "동시 답글", parent);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                ready.countDown(); await(start);
                return comments.create(fixture.owner().getId().toString(), fixture.videoId(), request);
            });
            var second = workers.submit(() -> {
                ready.countDown(); await(start);
                return comments.create(fixture.owner().getId().toString(), fixture.videoId(), request);
            });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                var one = first.get(15, TimeUnit.SECONDS);
                var two = second.get(15, TimeUnit.SECONDS);
                assertThat(one.comment().id()).isEqualTo(two.comment().id());
                assertThat(one.created()).isNotEqualTo(two.created());
                assertThat(count("video_comments", "video_id", fixture.videoId())).isEqualTo(2);
                assertThat(count("notifications", "user_id", fixture.friend().getId())).isEqualTo(1);
                assertThat(count("notification_outbox", "user_id", fixture.friend().getId())).isEqualTo(1);
            } finally {
                start.countDown();
                workers.shutdownNow();
            }
        }
    }

    @Test
    void rootDeletionCommittedWhileReplyWaitsPreventsReplyAndNotification() throws Exception {
        var fixture = fixture();
        long parent = comments.create(fixture.friend().getId().toString(), fixture.videoId(),
                new CommentCreateRequest(UUID.randomUUID(), "삭제될 원댓글")).comment().id();
        var gate = new Gate(fixture.videoId());
        repository.setGate(gate);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var result = worker.submit(() -> {
                try {
                    comments.create(fixture.owner().getId().toString(), fixture.videoId(),
                            new CommentCreateRequest(UUID.randomUUID(), "경합 답글", parent));
                    return "CREATED";
                } catch (GameException denied) {
                    return denied.code();
                }
            });
            try {
                assertThat(gate.visibilityChecked.await(10, TimeUnit.SECONDS)).isTrue();
                repository.setGate(null);
                comments.delete(fixture.friend().getId().toString(), parent);
                gate.continueLocking.countDown();
                assertThat(result.get(15, TimeUnit.SECONDS)).isEqualTo("COMMENT_NOT_FOUND");
                assertThat(count("video_comments", "video_id", fixture.videoId())).isEqualTo(1);
                assertThat(count("notifications", "user_id", fixture.friend().getId())).isZero();
                assertThat(count("notification_outbox", "user_id", fixture.friend().getId())).isZero();
            } finally {
                gate.continueLocking.countDown();
                repository.setGate(null);
                worker.shutdownNow();
            }
        }
    }

    private long count(String table, String key, long id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + key + " = ?", Long.class, id);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("동시성 테스트 동기화 시간 초과");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private record Fixture(User owner, User friend, long friendshipId, long videoId) {}
}
