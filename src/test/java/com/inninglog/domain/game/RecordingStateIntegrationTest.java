package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
@Import(VideoApiIntegrationTest.StorageConfig.class)
class RecordingStateIntegrationTest extends GameIntegrationSupport {
    @Autowired VideoApiIntegrationTest.FakeVideoStorage storage;
    @Autowired FriendshipRepository friendships;

    @Test
    void calendarGamesAndTimelineFollowUploadAndDeletionWithoutChangingRecordCounts() throws Exception {
        var owner = user();
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        assertSummary(owner, gameId, "NONE", 0);
        viewing(owner, gameId, team("LG"), "HOME");
        assertSummary(owner, gameId, "NONE", 0);

        long first = record(owner, gameId);
        assertSummary(owner, gameId, "UNAVAILABLE", 1);
        pending(owner, first);
        assertSummary(owner, gameId, "UNAVAILABLE", 1);
        complete(owner, first);
        assertSummary(owner, gameId, "READY", 1);

        long second = record(owner, gameId);
        pending(owner, second);
        assertSummary(owner, gameId, "READY", 2);
        mvc.perform(delete("/api/inning-records/{id}/video", first).with(as(owner)))
                .andExpect(status().isNoContent());
        assertSummary(owner, gameId, "UNAVAILABLE", 2);
        mvc.perform(delete("/api/inning-records/{id}", second).with(as(owner)))
                .andExpect(status().isNoContent());
        assertSummary(owner, gameId, "UNAVAILABLE", 1);
        mvc.perform(delete("/api/inning-records/{id}", first).with(as(owner)))
                .andExpect(status().isNoContent());
        assertSummary(owner, gameId, "NONE", 0);
    }

    @Test
    void friendsReadyVideoNeverBecomesTheViewersRecordingState() throws Exception {
        var owner = user();
        var viewer = user();
        var friendship = Friendship.request(owner, viewer, NOW);
        friendship.accept(NOW);
        friendships.saveAndFlush(friendship);
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        viewing(owner, gameId, team("LG"), "STADIUM");
        long record = record(owner, gameId);
        pending(owner, record);
        complete(owner, record);
        assertSummary(owner, gameId, "READY", 1);
        assertSummary(viewer, gameId, "NONE", 0);
        mvc.perform(get("/api/timelines/{id}", owner.getId()).with(as(viewer)).param("gameId", Long.toString(gameId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordCount").value(1))
                .andExpect(jsonPath("$.game.recordingState").value("NONE"))
                .andExpect(jsonPath("$.game.recordCount").value(0));

        viewing(viewer, gameId, team("LG"), "HOME");
        pending(viewer, record(viewer, gameId));
        mvc.perform(get("/api/timelines/{id}", owner.getId()).with(as(viewer)).param("gameId", Long.toString(gameId)))
                .andExpect(jsonPath("$.game.recordingState").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.game.recordCount").value(1));
        mvc.perform(get("/api/timelines/{id}", viewer.getId()).with(as(owner)).param("gameId", Long.toString(gameId)))
                .andExpect(jsonPath("$.game.recordingState").value("READY"));
    }

    @Test
    void hiddenViewingsAndDeletedRecordsDoNotExposeRetainedVideos() throws Exception {
        var owner = user();
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        long logId = viewing(owner, gameId, team("LG"), "STADIUM");
        long record = record(owner, gameId);
        pending(owner, record);
        complete(owner, record);
        sqlUpdate("update user_game_logs set deleted_at = ? where id = ?", NOW, logId);
        assertSummary(owner, gameId, "NONE", 0);
        mvc.perform(post("/api/user-game-logs").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(registration(gameId, "STADIUM"))).andExpect(status().isOk());
        assertSummary(owner, gameId, "READY", 1);

        // Older rows may still have a retained READY video after their parent record was deleted.
        sqlUpdate("update inning_records set deleted_at = ? where id = ?", NOW, record);
        assertSummary(owner, gameId, "NONE", 0);
    }

    @Test
    void doubleheaderAndMonthBoundariesKeepAvailabilityOnTheCorrectGame() throws Exception {
        var owner = user();
        var first = game(TODAY, "FINISHED", 5, 3);
        first.put("gameSequence", 1);
        var second = game(TODAY, "FINISHED", 1, 3);
        second.put("gameSequence", 2);
        second.put("scheduledAt", TODAY + "T12:30:00Z");
        long firstId = ingest(first);
        long secondId = ingest(second);
        viewing(owner, firstId, team("LG"), "STADIUM");
        viewing(owner, secondId, team("LG"), "HOME");
        long record = record(owner, secondId);
        pending(owner, record);
        complete(owner, record);
        assertSummary(owner, firstId, "NONE", 0);
        assertSummary(owner, secondId, "READY", 1);
        assertThat(games.recordingSummariesForRange(owner.getId(), TODAY.withDayOfMonth(1).minusMonths(1), TODAY.withDayOfMonth(1)))
                .isEmpty();
    }

    private long record(User owner, long gameId) throws Exception {
        var body = Map.of("gameId", gameId, "clientRecordId", UUID.randomUUID(), "inning", 1,
                "half", "TOP", "recordedAt", NOW.minusSeconds(30).toString(), "text", "기록");
        return mapper.readTree(mvc.perform(post("/api/inning-records").with(as(owner)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(body))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).path("id").longValue();
    }

    private void pending(User owner, long recordId) throws Exception {
        mvc.perform(post("/api/inning-records/{id}/video/upload-url", recordId).with(as(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"contentType\":\"video/mp4\",\"contentLength\":10}"))
                .andExpect(status().isOk());
        storage.store(storage.lastKey(), 10, "video/mp4");
    }

    private void complete(User owner, long recordId) throws Exception {
        mvc.perform(post("/api/inning-records/{id}/video/complete", recordId).with(as(owner)))
                .andExpect(status().isOk());
    }

    private void assertSummary(User user, long gameId, String expected, int count) throws Exception {
        var daily = mapper.readTree(mvc.perform(get("/api/games").with(as(user)).param("date", TODAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var calendar = mapper.readTree(mvc.perform(get("/api/home/calendar").with(as(user))
                        .param("year", "2026").param("month", "9"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertGame(findGame(daily.path("games"), gameId), expected, count);
        assertGame(findGame(calendar.path("days").get(13).path("games"), gameId), expected, count);
        mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", Long.toString(gameId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.game.recordingState").value(expected))
                .andExpect(jsonPath("$.game.recordCount").value(count))
                .andExpect(jsonPath("$.game.hasRecords").value(count > 0));
    }

    private JsonNode findGame(JsonNode list, long gameId) {
        for (var item : list) if (item.path("gameId").longValue() == gameId) return item;
        throw new AssertionError("Game not returned: " + gameId);
    }

    private void assertGame(JsonNode game, String state, int count) {
        assertThat(game.path("recordingState").stringValue()).isEqualTo(state);
        assertThat(game.path("recordCount").longValue()).isEqualTo(count);
        assertThat(game.path("hasRecords").booleanValue()).isEqualTo(count > 0);
    }
}
