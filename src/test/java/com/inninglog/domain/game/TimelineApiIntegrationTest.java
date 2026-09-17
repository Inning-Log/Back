package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.friendship.entity.Friendship;
import com.inninglog.domain.friendship.repository.FriendshipRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.service.AccountDeletionService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
class TimelineApiIntegrationTest extends GameIntegrationSupport {
    @Autowired FriendshipRepository friendships;
    @Autowired AccountDeletionService accountDeletion;

    @Test
    void validatesAuthenticationIdentifiersAndRequestBodies() throws Exception {
        var user = user();
        mvc.perform(get("/api/timelines/me").param("gameId", "1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/timelines/profiles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/inning-records/1")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inning-records").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        for (String value : List.of("2026-09-14", "0", "-1")) {
            mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", value))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        mvc.perform(get("/api/timelines/me").with(as(user))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/timelines/@friend").with(as(user)).param("gameId", "1")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/timelines/profiles").with(as(user)).param("limit", "101")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/timelines/profiles").with(as(user)).param("afterUserId", "-1")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        var body = input(1, 1, NOW.minusSeconds(30));
        for (Map<String,Object> invalid : List.of(Map.<String,Object>of("inning", 0), Map.<String,Object>of("inning", 100),
                Map.<String,Object>of("clientRecordId", "bad"), Map.<String,Object>of("text", "x".repeat(256)),
                Map.<String,Object>of("half", "UNKNOWN"))) {
            var request = new LinkedHashMap<>(body);
            request.putAll(invalid);
            mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void multipleRecordsInSameInningAndHalfRemainSeparateAndAppearOnCalendar() throws Exception {
        var user = user();
        long gameId = setup(user);
        long third = create(user, input(gameId, 1, NOW.minusSeconds(10))).path("id").longValue();
        long first = create(user, input(gameId, 1, NOW.minusSeconds(50))).path("id").longValue();
        long second = create(user, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        var timeline = timeline(user, gameId);
        assertThat(timeline.path("records").size()).isEqualTo(3);
        assertThat(List.of(timeline.path("records").get(0).path("id").longValue(),
                timeline.path("records").get(1).path("id").longValue(), timeline.path("records").get(2).path("id").longValue()))
                .containsExactly(first, second, third);
        assertThat(timeline.path("canCreateRecord").booleanValue()).isTrue();
        assertThat(timeline.path("recordCount").longValue()).isEqualTo(3);
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year", "2026").param("month", "9"))
                .andExpect(jsonPath("$.days[13].games[0].gameId").value(gameId))
                .andExpect(jsonPath("$.days[13].games[0].recordCount").value(3))
                .andExpect(jsonPath("$.days[13].games[0].hasRecords").value(true));
        mvc.perform(get("/api/games").with(as(user)).param("date", TODAY.toString()))
                .andExpect(jsonPath("$.games[0].recordCount").value(3));
        mvc.perform(get("/api/home/win-rate").with(as(user)))
                .andExpect(jsonPath("$.registeredCount").value(1)).andExpect(jsonPath("$.winRatePercent").value(100.0));
    }

    @Test
    void retryUsesOriginalRequestEvenAfterTextEditOrGameDateChange() throws Exception {
        var user = user();
        long gameId = setup(user);
        var body = input(gameId, 9, NOW.minusSeconds(30));
        long id = create(user, body).path("id").longValue();
        mvc.perform(patch("/api/inning-records/{id}", id).with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"수정한 문구\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("수정한 문구"));
        jdbc.update("update games set game_date = ? where id = ?", TODAY.minusDays(1), gameId);
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.text").value("수정한 문구"));
        body.put("text", "다른 최초 요청");
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_REQUEST_CONFLICT"));
        assertThat(timeline(user, gameId).path("canCreateRecord").booleanValue()).isFalse();
    }

    @Test
    void requiresViewingAndRejectsWrongDayFutureCaptureAndCanceledGames() throws Exception {
        var user = user();
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        assertThat(timeline(user, gameId).path("requiresViewingRegistration").booleanValue()).isTrue();
        assertThat(timeline(user, gameId).path("canCreateRecord").booleanValue()).isFalse();
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input(gameId, 1, NOW))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIEWING_REQUIRED"));
        viewing(user, gameId, team("LG"), "STADIUM");
        for (Instant captured : List.of(NOW.plusSeconds(1), NOW.minusSeconds(86400))) {
            mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input(gameId, 1, captured))))
                    .andExpect(status().isBadRequest());
        }
        jdbc.update("update games set status = 'CANCELED' where id = ?", gameId);
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input(gameId, 1, NOW))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME_NOT_RECORDABLE"));
        jdbc.update("update games set status = 'FINISHED', game_date = ? where id = ?", TODAY.minusDays(1), gameId);
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input(gameId, 1, NOW))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_DATE_NOT_TODAY"));
    }

    @Test
    void recordedScoreIsImmutableAndNeverTakenFromClient() throws Exception {
        var user = user();
        long gameId = setup(user);
        var body = input(gameId, 1, NOW.minusSeconds(30));
        body.put("homeScore", 999);
        body.put("awayScore", 998);
        var record = create(user, body);
        assertThat(record.path("homeScore").intValue()).isEqualTo(5);
        assertThat(record.path("awayScore").intValue()).isEqualTo(3);
        assertThat(record.path("scoreObservedAt").asText()).isEqualTo(OBSERVED.toString());
        jdbc.update("update games set home_score = 10, away_score = 8 where id = ?", gameId);
        mvc.perform(get("/api/inning-records/{id}", record.path("id").longValue()).with(as(user)))
                .andExpect(jsonPath("$.homeScore").value(5)).andExpect(jsonPath("$.awayScore").value(3))
                .andExpect(jsonPath("$.videoStatus").value("UNAVAILABLE"));
    }

    @Test
    void scoreHistoryUsesCaptureTimeAndDoesNotExpireAfterFiveMinutes() throws Exception {
        var user = user();
        var futureState = game(TODAY, "LIVE", 9, 8);
        futureState.put("awayTeam", Map.of("code", "NC"));
        long futureGameId = ingest(futureState);
        viewing(user, futureGameId, team("LG"), "STADIUM");
        var beforeFirstObservation = create(user, input(futureGameId, 1, OBSERVED.minusSeconds(1)));
        assertThat(beforeFirstObservation.path("homeScore").isNull()).isTrue();
        assertThat(beforeFirstObservation.path("awayScore").isNull()).isTrue();

        var oldState = game(TODAY, "LIVE", 5, 3);
        importer.importMessage(message(List.of(oldState), NOW.minusSeconds(900), false));
        long gameId = jdbc.queryForObject("select game_id from game_external_ids where external_id = ?", Long.class,
                ((Map<?,?>)oldState.get("externalId")).get("kbo"));
        viewing(user, gameId, team("LG"), "STADIUM");
        var earlierCapture = create(user, input(gameId, 1, OBSERVED.minusSeconds(1)));
        assertThat(earlierCapture.path("homeScore").intValue()).isEqualTo(5);
        assertThat(earlierCapture.path("awayScore").intValue()).isEqualTo(3);
        assertThat(earlierCapture.path("scoreObservedAt").asText()).isEqualTo(NOW.minusSeconds(900).toString());

        oldState.put("score", Map.of("home",2,"away",5));
        importer.importMessage(message(List.of(oldState), NOW.minusSeconds(60), false));
        var beforeComeback = create(user, input(gameId, 5, NOW.minusSeconds(120)));
        var afterComeback = create(user, input(gameId, 7, NOW.minusSeconds(30)));
        assertThat(beforeComeback.path("homeScore").intValue()).isEqualTo(5);
        assertThat(beforeComeback.path("awayScore").intValue()).isEqualTo(3);
        assertThat(afterComeback.path("homeScore").intValue()).isEqualTo(2);
        assertThat(afterComeback.path("awayScore").intValue()).isEqualTo(5);
    }

    @Test
    void friendReadsRequireAcceptanceAndWritesRemainOwnerOnly() throws Exception {
        var owner = user();
        var viewer = user();
        long gameId = setup(owner);
        long recordId = create(owner, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        var friendship = friendships.saveAndFlush(Friendship.request(owner, viewer, NOW));
        assertFriendDenied(viewer, owner, gameId, recordId);
        friendship.accept(NOW);
        friendships.flush();
        mvc.perform(get("/api/timelines/{userId}", owner.getId()).with(as(viewer)).param("gameId", "" + gameId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.owner.userId").value(owner.getId()))
                .andExpect(jsonPath("$.records.length()").value(1)).andExpect(jsonPath("$.canCreateRecord").value(false))
                .andExpect(jsonPath("$.viewing.viewingType").value("STADIUM"))
                .andExpect(jsonPath("$.game.myViewing").value(nullValue())).andExpect(jsonPath("$.game.recordCount").value(0));
        mvc.perform(get("/api/inning-records/{id}", recordId).with(as(viewer))).andExpect(status().isOk());
        mvc.perform(patch("/api/inning-records/{id}", recordId).with(as(viewer)).contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"침입\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/inning-records/{id}", recordId).with(as(viewer))).andExpect(status().isNotFound());
        friendships.delete(friendship);
        friendships.flush();
        assertFriendDenied(viewer, owner, gameId, recordId);
    }

    @Test
    void profilesUseNumericIdsFilterFriendsAndSeparateDoubleheaderGames() throws Exception {
        var me = user();
        var friend = user();
        var pending = user();
        var nextFriend = user();
        befriend(friend, me);
        befriend(me, nextFriend);
        friendships.saveAndFlush(Friendship.request(me, pending, NOW));
        long first = setup(friend);
        var secondGame = game(TODAY, "FINISHED", 0, 2);
        secondGame.put("gameSequence", 2);
        long second = ingest(secondGame);
        viewing(friend, second, team("LG"), "HOME");
        create(friend, input(first, 1, NOW.minusSeconds(30)));
        create(friend, input(second, 1, NOW.minusSeconds(20)));
        mvc.perform(get("/api/timelines/profiles").with(as(me)).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.me.userId").value(me.getId()))
                .andExpect(jsonPath("$.me.hasRecordedToday").value(false))
                .andExpect(jsonPath("$.friends.length()").value(1)).andExpect(jsonPath("$.friends[0].userId").value(friend.getId()))
                .andExpect(jsonPath("$.friends[0].hasRecordedToday").value(true))
                .andExpect(jsonPath("$.friends[0].games.length()").value(2)).andExpect(jsonPath("$.nextAfterUserId").value(friend.getId()));
        mvc.perform(get("/api/timelines/profiles").with(as(me)).param("afterUserId", "" + friend.getId()))
                .andExpect(jsonPath("$.friends.length()").value(1)).andExpect(jsonPath("$.friends[0].userId").value(nextFriend.getId()))
                .andExpect(jsonPath("$.nextAfterUserId").value(nullValue()));
        assertThat(timeline(friend, first).path("records").size()).isEqualTo(1);
        assertThat(timeline(friend, second).path("records").size()).isEqualTo(1);
    }

    @Test
    void paginationPreservesEqualTimestampsAndSurvivesDeletedBoundary() throws Exception {
        var user = user();
        long gameId = setup(user);
        long id1 = create(user, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        long id2 = create(user, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        long id3 = create(user, input(gameId, 10, NOW.minusSeconds(20))).path("id").longValue();
        var first = mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", "" + gameId).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.records[0].id").value(id1)).andReturn();
        String cursor = mapper.readTree(first.getResponse().getContentAsString()).path("nextCursor").asText();
        mvc.perform(delete("/api/inning-records/{id}", id1).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", "" + gameId).param("cursor", cursor))
                .andExpect(jsonPath("$.records[0].id").value(id2)).andExpect(jsonPath("$.records[1].id").value(id3))
                .andExpect(jsonPath("$.nextCursor").value(nullValue()));
        var otherGame = game(TODAY, "FINISHED", 1, 0);
        otherGame.put("gameSequence", 2);
        long otherId = ingest(otherGame);
        viewing(user, otherId, team("LG"), "HOME");
        mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", "" + otherId).param("cursor", cursor))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", "" + gameId).param("cursor", "garbage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingLastRecordDoesNotDeleteViewingOrChangeWinRate() throws Exception {
        var user = user();
        long gameId = setup(user);
        var request = input(gameId, 1, NOW.minusSeconds(30));
        long id = create(user, request).path("id").longValue();
        for (int i = 0; i < 2; i++)
            mvc.perform(delete("/api/inning-records/{id}", id).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(get("/api/inning-records/{id}", id).with(as(user))).andExpect(status().isNotFound());
        mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORD_DELETED"));
        assertThat(timeline(user, gameId).path("recordCount").longValue()).isZero();
        mvc.perform(get("/api/games").with(as(user)).param("date", TODAY.toString()))
                .andExpect(jsonPath("$.games[0].hasRecords").value(false)).andExpect(jsonPath("$.games[0].myViewing").isNotEmpty());
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.winRatePercent").value(100.0));
    }

    @Test
    void deletingAndRestoringViewingDoesNotResurrectOldRecords() throws Exception {
        var user = user();
        long gameId = setup(user);
        long id = create(user, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        long logId = games.logForGame(user.getId(), gameId).orElseThrow().id();
        mvc.perform(delete("/api/user-game-logs/{id}", logId).with(as(user))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select deleted_at is not null from inning_records where id = ?", Boolean.class, id)).isTrue();
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(registration(gameId, "STADIUM")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/inning-records/{id}", id).with(as(user))).andExpect(status().isNotFound());
        assertThat(timeline(user, gameId).path("recordCount").longValue()).isZero();
    }

    @Test
    void accountDeletionRemovesRecordsFromFriendTimelineAndProfiles() throws Exception {
        var owner = user();
        var friend = user();
        befriend(owner, friend);
        long gameId = setup(owner);
        long id = create(owner, input(gameId, 1, NOW.minusSeconds(30))).path("id").longValue();
        accountDeletion.deleteCurrentUser(owner.getId().toString());
        assertThat(jdbc.queryForObject("select deleted_at is not null from inning_records where id = ?", Boolean.class, id)).isTrue();
        assertFriendDenied(friend, owner, gameId, id);
        mvc.perform(get("/api/timelines/profiles").with(as(friend))).andExpect(jsonPath("$.friends.length()").value(0));
    }

    @Test
    void swaggerExposesGameIdAndTimelineRecordOperations() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/timelines/me'].get.parameters[?(@.name == 'gameId')].required").value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$.paths['/api/timelines/{userId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/inning-records'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/inning-records/{id}'].patch").exists());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousRetriesCreateOneRecord() throws Exception {
        var user = user();
        long gameId = setup(user);
        long logId = games.logForGame(user.getId(), gameId).orElseThrow().id();
        String body = mapper.writeValueAsString(input(gameId, 1, NOW.minusSeconds(30)));
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> {
                gate.await(5, TimeUnit.SECONDS);
                return mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(call);
            var b = executor.submit(call);
            gate.countDown();
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(201, 200);
            assertThat(jdbc.queryForObject("select count(*) from inning_records where user_game_log_id = ?", Long.class, logId)).isEqualTo(1);
        } finally {
            jdbc.update("delete from inning_records where user_game_log_id = ?", logId);
            jdbc.update("delete from user_game_logs where id = ?", logId);
            jdbc.update("delete from game_external_ids where game_id = ?", gameId);
            jdbc.update("delete from games where id = ?", gameId);
            users.deleteById(user.getId());
            jdbc.update("delete from game_sync_scopes where scope_key = ?", "DAY:" + TODAY);
        }
    }

    private long setup(User user) {
        long id = ingest(game(TODAY, "FINISHED", 5, 3));
        viewing(user, id, team("LG"), "STADIUM");
        return id;
    }

    private Map<String,Object> input(long gameId, int inning, Instant captured) {
        var result = new LinkedHashMap<String,Object>();
        result.put("gameId", gameId);
        result.put("clientRecordId", UUID.randomUUID().toString());
        result.put("inning", inning);
        result.put("half", "TOP");
        result.put("recordedAt", captured.toString());
        result.put("text", "오늘의 관람 기록");
        return result;
    }

    private JsonNode create(User user, Map<String,Object> input) throws Exception {
        var response = mvc.perform(post("/api/inning-records").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)))
                .andExpect(status().isCreated()).andExpect(header().exists("Location")).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private JsonNode timeline(User user, long gameId) throws Exception {
        var response = mvc.perform(get("/api/timelines/me").with(as(user)).param("gameId", "" + gameId))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(response.getResponse().getContentAsString());
    }

    private void befriend(User from, User to) {
        var friendship = Friendship.request(from, to, NOW);
        friendship.accept(NOW);
        friendships.saveAndFlush(friendship);
    }

    private void assertFriendDenied(User viewer, User owner, long gameId, long recordId) throws Exception {
        mvc.perform(get("/api/timelines/{userId}", owner.getId()).with(as(viewer)).param("gameId", "" + gameId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/inning-records/{id}", recordId).with(as(viewer))).andExpect(status().isNotFound());
    }
}
