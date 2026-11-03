package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.gamevideo.queue.GameVideoQueue;
import com.inninglog.domain.gamevideo.service.GameVideoJobs;
import com.inninglog.domain.gamevideo.service.GameVideoRecoveryScheduler;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.video.repository.VideoRepository;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

// Not @Transactional: jobs are queued after commit. Committed rows go to a dedicated in-memory database.
@Import(GameVideoApiIntegrationTest.FakesConfig.class)
class GameVideoApiIntegrationTest extends GameIntegrationSupport {
    private static final List<String> OPPONENTS = List.of("KT", "NC", "SSG", "KIA", "DOO", "HH", "LT", "SS", "WO");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (System.getenv("GAME_TEST_JDBC_URL") == null)
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:game_video_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        registry.add("app.game-videos.enabled", () -> "true");
        registry.add("app.game-videos.job-queue-url", () -> "https://sqs.ap-northeast-2.amazonaws.com/000000000000/jobs");
        registry.add("app.game-videos.result-queue-url", () -> "https://sqs.ap-northeast-2.amazonaws.com/000000000000/results");
        // Tests drive the consumer and the recovery scheduler by hand.
        registry.add("app.game-videos.initial-delay", () -> "1h");
        registry.add("app.game-videos.recovery-initial-delay", () -> "1h");
    }

    @Autowired FakeQueue queue;
    @Autowired VideoApiIntegrationTest.FakeVideoStorage storage;
    @Autowired VideoRepository clipVideos;
    @Autowired FriendshipRepository friendships;
    @Autowired GameVideoJobs jobs;
    @Autowired GameVideoRecoveryScheduler recovery;

    @TestConfiguration
    static class FakesConfig {
        @Bean @Primary FakeQueue fakeGameVideoQueue() { return new FakeQueue(); }
        @Bean @Primary VideoApiIntegrationTest.FakeVideoStorage fakeVideoStorage() { return new VideoApiIntegrationTest.FakeVideoStorage(); }
    }

    static class FakeQueue implements GameVideoQueue {
        final List<String> jobs = Collections.synchronizedList(new ArrayList<>());
        @Override public void sendJob(String body) { jobs.add(body); }
        @Override public List<Message> receiveResults(int max) { return List.of(); }
        @Override public void acknowledge(Message message) {}
    }

    long gameId;

    @BeforeEach
    void newGame() {
        var game = game(TODAY, "FINISHED", 5, 3);
        String opponent = OPPONENTS.get(SEQUENCE.getAndIncrement() % OPPONENTS.size());
        game.put("awayTeam", Map.of("code", opponent));
        game.put("externalId", Map.of("kbo", "game-video-" + opponent));
        gameId = ingest(game);
        queue.jobs.clear();
    }

    @Test
    void rendersEachInningThenMergesAndNotifies() throws Exception {
        var owner = viewer();
        clip(owner, 3, "BOTTOM", 50, "셋째 회");
        clip(owner, 1, "TOP", 300, "첫 영상");
        clip(owner, 1, "TOP", 200, "두번째 영상");

        var created = request(owner, List.of()).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("RENDERING")).andExpect(jsonPath("$.mode").value("SOLO"))
                .andExpect(jsonPath("$.segmentCount").value(2)).andExpect(jsonPath("$.readySegmentCount").value(0));
        assertThat(created.andReturn().getResponse().getContentAsString()).doesNotContain("downloadUrl\":\"http");

        var renders = jobs("RENDER_SEGMENT");
        assertThat(renders).hasSize(2);
        var first = renders.getFirst().path("segment");
        assertThat(first.path("inning").intValue()).isEqualTo(1);
        assertThat(first.path("half").stringValue()).isEqualTo("TOP");
        assertThat(first.path("solo").booleanValue()).isTrue();
        assertThat(first.path("header").path("homeTeamCode").stringValue()).isEqualTo("LG");
        assertThat(first.path("footer").path("handle").stringValue()).isEqualTo(owner.getUsername());
        var ownClips = first.path("members").get(0).path("clips");
        assertThat(ownClips).hasSize(2);
        // capture order within the inning
        assertThat(ownClips.get(0).path("caption").stringValue()).isEqualTo("첫 영상");
        assertThat(ownClips.get(1).path("caption").stringValue()).isEqualTo("두번째 영상");
        assertThat(renders.get(1).path("segment").path("inning").intValue()).isEqualTo(3);

        // A second request while rendering is refused.
        request(owner, List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME_VIDEO_IN_PROGRESS"));

        segmentResult(renders.get(1), "READY");
        assertThat(jobs("MERGE_GAME")).isEmpty();
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.readySegmentCount").value(1));
        segmentResult(renders.getFirst(), "READY");
        var merge = jobs("MERGE_GAME");
        assertThat(merge).hasSize(1);
        assertThat(merge.getFirst().path("segmentKeys").get(0).stringValue())
                .isEqualTo(renders.getFirst().path("outputKey").stringValue());
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.status").value("MERGING"));

        long videoId = merge.getFirst().path("gameVideoId").longValue();
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "MERGE_RESULT", "gameVideoId", videoId,
                "status", "READY", "durationSeconds", 12.5)));
        String outputKey = merge.getFirst().path("outputKey").stringValue();
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.durationSeconds").value(12.5))
                .andExpect(jsonPath("$.downloadUrl").value("https://bucket.example/" + outputKey + "?download"));
        assertThat(notifications(owner, "GENERATED_VIDEO_READY")).isEqualTo(1);
        // A repeated result changes nothing and does not notify twice.
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "MERGE_RESULT", "gameVideoId", videoId, "status", "READY")));
        assertThat(notifications(owner, "GENERATED_VIDEO_READY")).isEqualTo(1);

        mvc.perform(delete(path()).with(as(owner))).andExpect(status().isNoContent());
        assertThat(storage.deleted).contains(outputKey);
        mvc.perform(get(path()).with(as(owner))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GAME_VIDEO_NOT_FOUND"));
        mvc.perform(delete(path()).with(as(owner))).andExpect(status().isNoContent());
    }

    @Test
    void reusesUnchangedInningsAndRerendersOnlyTheEditedOne() throws Exception {
        var owner = viewer();
        long edited = clip(owner, 1, "TOP", 300, "원래 캡션");
        clip(owner, 2, "TOP", 200, "그대로");
        request(owner, List.of()).andExpect(status().isAccepted());
        var renders = jobs("RENDER_SEGMENT");
        for (var render : renders) {
            storage.store(render.path("outputKey").stringValue(), 100, "video/mp4");
            segmentResult(render, "READY");
        }
        long videoId = jobs("MERGE_GAME").getFirst().path("gameVideoId").longValue();
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "MERGE_RESULT", "gameVideoId", videoId, "status", "READY")));

        // Unchanged: no rendering at all, straight to merging.
        queue.jobs.clear();
        request(owner, List.of()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("MERGING"));
        assertThat(jobs("RENDER_SEGMENT")).isEmpty();
        assertThat(jobs("MERGE_GAME")).hasSize(1);
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "MERGE_RESULT",
                "gameVideoId", jobs("MERGE_GAME").getFirst().path("gameVideoId").longValue(), "status", "READY")));

        // Editing one caption renders only that inning.
        sqlUpdate("update inning_records set caption = ? where id = ?", "바뀐 캡션", edited);
        queue.jobs.clear();
        request(owner, List.of()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RENDERING"));
        var rerendered = jobs("RENDER_SEGMENT");
        assertThat(rerendered).hasSize(1);
        assertThat(rerendered.getFirst().path("segment").path("members").get(0).path("clips").get(0).path("caption").stringValue())
                .isEqualTo("바뀐 캡션");

        // A cached segment whose file expired from the bucket is rendered again.
        storage.store(rerendered.getFirst().path("outputKey").stringValue(), 100, "video/mp4");
        segmentResult(rerendered.getFirst(), "READY");
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "MERGE_RESULT",
                "gameVideoId", jobs("MERGE_GAME").getFirst().path("gameVideoId").longValue(), "status", "READY")));
        storage.objects.remove(renders.get(1).path("outputKey").stringValue());
        queue.jobs.clear();
        request(owner, List.of()).andExpect(status().isAccepted());
        assertThat(jobs("RENDER_SEGMENT")).extracting(job -> job.path("renderKey").stringValue())
                .containsExactly(renders.get(1).path("renderKey").stringValue());
    }

    @Test
    void includesSelectedFriendsWithEmptyCardsForInningsWithoutTheirClips() throws Exception {
        var owner = viewer();
        var friend = viewer();
        var stranger = viewer();
        var friendWithoutClips = viewer();
        befriend(owner, friend);
        befriend(friendWithoutClips, owner);
        clip(owner, 1, "TOP", 300, "내 영상");
        clip(friend, 1, "TOP", 250, "친구 영상");
        clip(friend, 2, "BOTTOM", 100, "친구만 있는 이닝");
        clip(stranger, 1, "TOP", 100, "모르는 사람");

        mvc.perform(get(path() + "/friends").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxFriends").value(5))
                .andExpect(jsonPath("$.friends.length()").value(1))
                .andExpect(jsonPath("$.friends[0].userId").value(friend.getId()))
                .andExpect(jsonPath("$.friends[0].clipCount").value(2));

        request(owner, List.of(stranger.getId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FRIEND"));
        request(owner, List.of(friendWithoutClips.getId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FRIEND"));
        request(owner, List.of(1L, 2L, 3L, 4L, 5L, 6L)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_FRIENDS"));

        request(owner, List.of(friend.getId())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.mode").value("WITH_FRIENDS"))
                .andExpect(jsonPath("$.memberUserIds[0]").value(owner.getId()))
                .andExpect(jsonPath("$.memberUserIds[1]").value(friend.getId()));
        var renders = jobs("RENDER_SEGMENT");
        assertThat(renders).hasSize(2);
        var second = renders.get(1).path("segment");
        assertThat(second.path("solo").booleanValue()).isFalse();
        assertThat(second.path("members").get(0).path("clips")).isEmpty();
        assertThat(second.path("members").get(1).path("clips").get(0).path("caption").stringValue()).isEqualTo("친구만 있는 이닝");
        assertThat(second.path("footer").path("handle").stringValue()).isEqualTo(owner.getUsername());
    }

    @Test
    void failedSegmentFailsTheVideoAndALaterRequestRetriesIt() throws Exception {
        var owner = viewer();
        request(owner, List.of()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_CLIPS"));
        clip(owner, 1, "TOP", 100, "영상");
        request(owner, List.of()).andExpect(status().isAccepted());
        var render = jobs("RENDER_SEGMENT").getFirst();
        segmentResult(render, "FAILED");
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("SEGMENT_FAILED"));
        assertThat(notifications(owner, "GENERATED_VIDEO_FAILED")).isEqualTo(1);

        queue.jobs.clear();
        request(owner, List.of()).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("RENDERING"));
        assertThat(jobs("RENDER_SEGMENT")).extracting(job -> job.path("renderKey").stringValue())
                .containsExactly(render.path("renderKey").stringValue());
    }

    @Test
    void recoveryResendsLostMergesAndTimesOutStuckJobs() throws Exception {
        var owner = viewer();
        clip(owner, 1, "TOP", 100, "영상");
        request(owner, List.of()).andExpect(status().isAccepted());
        var render = jobs("RENDER_SEGMENT").getFirst();
        // The worker finished, but the merge job was lost: mark the segment READY without going through the handler.
        sqlUpdate("update segment_renders set status = 'READY' where render_key = ?", render.path("renderKey").stringValue());
        queue.jobs.clear();
        recovery.recover();
        assertThat(jobs("MERGE_GAME")).hasSize(1);
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.status").value("MERGING"));

        // No merge result for longer than the timeout.
        sqlUpdate("update game_videos set merge_requested_at = ? where user_id = ?", NOW.minus(Duration.ofHours(2)), owner.getId());
        recovery.recover();
        mvc.perform(get(path()).with(as(owner))).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").value("TIMEOUT"));
    }

    private User viewer() {
        var user = user();
        viewing(user, gameId, team("LG"), "STADIUM");
        return user;
    }

    /** An inning record with a READY video, recorded {@code secondsAgo} before now. */
    private long clip(User user, int inning, String half, int secondsAgo, String caption) throws Exception {
        var body = Map.of("gameId", gameId, "clientRecordId", UUID.randomUUID().toString(), "inning", inning,
                "half", half, "recordedAt", NOW.minusSeconds(secondsAgo).toString(), "text", caption);
        var response = mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn();
        long recordId = mapper.readTree(response.getResponse().getContentAsString()).path("id").longValue();
        long videoId = clipVideos.insertPending(recordId, "inning-records/test/" + UUID.randomUUID() + ".mp4", "video/mp4", 10, NOW);
        clipVideos.markReady(videoId, NOW);
        return recordId;
    }

    private org.springframework.test.web.servlet.ResultActions request(User user, List<Long> friendIds) throws Exception {
        return mvc.perform(post(path()).with(as(user)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("friendIds", friendIds))));
    }

    private void segmentResult(JsonNode render, String status) {
        jobs.handleResult(mapper.writeValueAsString(Map.of("type", "SEGMENT_RESULT",
                "renderKey", render.path("renderKey").stringValue(), "status", status, "durationSeconds", 4.0)));
    }

    private List<JsonNode> jobs(String type) {
        synchronized (queue.jobs) {
            return queue.jobs.stream().map(mapper::readTree).filter(job -> type.equals(job.path("type").stringValue())).toList();
        }
    }

    private int notifications(User user, String type) {
        return jdbc.queryForObject("select count(*) from notifications where user_id = ? and notification_type = ?",
                Integer.class, user.getId(), type);
    }

    private String path() { return "/api/games/" + gameId + "/video"; }

    private void befriend(User from, User to) {
        var friendship = Friendship.request(from, to, NOW);
        friendship.accept(NOW);
        friendships.saveAndFlush(friendship);
    }
}
