package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.user.service.AccountDeletionService;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GameApiIntegrationTest extends GameIntegrationSupport {
    @Autowired AccountDeletionService deletion;

    @Test
    void endpointsRequireAuthenticationAndValidateInputs() throws Exception {
        var user = user();
        mvc.perform(get("/api/home/calendar").param("year","2026").param("month","9")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/home/win-rate")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/games").param("date",TODAY.toString())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/user-game-logs").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2026").param("month","13"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/games").with(as(user)).param("date","2026-02-30")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/games").with(as(user))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{\"viewingType\":\"OTHER\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/dev/game-snapshots").with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void registerRetryCorrectDeleteAndRestoreOneViewing() throws Exception {
        var user = user();
        long game = ingest(game(TODAY,"FINISHED",5,3));
        String body = registration(game,"STADIUM");
        var response = mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.result").value("WIN")).andReturn();
        long id = mapper.readTree(response.getResponse().getContentAsString()).path("id").longValue();
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(registration(game,"HOME")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIEWING_ALREADY_EXISTS"));
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.winRatePercent").value(100.0));
        mvc.perform(patch("/api/user-game-logs/{id}",id).with(as(user)).contentType(MediaType.APPLICATION_JSON).content("{\"viewingType\":\"HOME\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewingType").value("HOME"));
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.registeredCount").value(0))
                .andExpect(jsonPath("$.winRatePercent").value(nullValue()));
        mvc.perform(delete("/api/user-game-logs/{id}",id).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/user-game-logs/{id}",id).with(as(user))).andExpect(status().isNoContent());
        mvc.perform(get("/api/user-game-logs/{id}",id).with(as(user))).andExpect(status().isNotFound());
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id));
        assertThat(jdbc.queryForObject("select count(*) from user_game_logs where user_id=? and game_id=?",Long.class,user.getId(),game)).isEqualTo(1);
    }

    @Test
    void currentRegularSeasonRateIgnoresHomeViewingOtherUsersAndOtherSeasons() throws Exception {
        var user = user();
        var other = user();
        for (int i = 0; i < 13; i++) {
            long game = ingest(game(LocalDate.of(2026,2,1).plusDays(i),i == 12 ? "LIVE" : "FINISHED", i < 7 ? 5 : i == 11 ? 3 : 1,3));
            viewing(user,game,team("LG"),"STADIUM");
        }
        long home = ingest(game(LocalDate.of(2026,3,1),"FINISHED",0,5));
        viewing(user,home,team("LG"),"HOME");
        viewing(other,home,team("LG"),"STADIUM");
        long prior = ingest(game(LocalDate.of(2025,9,1),"FINISHED",0,5));
        viewing(user,prior,team("LG"),"STADIUM");
        var playoffs = game(LocalDate.of(2026,3,2),"FINISHED",0,5);
        playoffs.put("gameType","PLAYOFF");
        viewing(user,ingest(playoffs),team("LG"),"STADIUM");
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$.seasonYear").value(2026)).andExpect(jsonPath("$.registeredCount").value(13))
                .andExpect(jsonPath("$.wins").value(7)).andExpect(jsonPath("$.losses").value(4))
                .andExpect(jsonPath("$.draws").value(1)).andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.decidedCount").value(11)).andExpect(jsonPath("$.winRatePercent").value(63.6));
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2025").param("month","9")).andExpect(status().isOk());
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.seasonYear").value(2026)).andExpect(jsonPath("$.wins").value(7));
    }

    @Test
    void emptyDrawOnlyZeroPercentAndExcludedResultsAreDistinct() throws Exception {
        var user = user();
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.winRatePercent").value(nullValue()));
        viewing(user,ingest(game(LocalDate.of(2026,1,1),"FINISHED",3,3)),team("LG"),"STADIUM");
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.draws").value(1)).andExpect(jsonPath("$.winRatePercent").value(nullValue()));
        viewing(user,ingest(game(LocalDate.of(2026,1,2),"FINISHED",0,3)),team("LG"),"STADIUM");
        viewing(user,ingest(game(LocalDate.of(2026,1,3),"CANCELLED",null,null)),team("LG"),"STADIUM");
        viewing(user,ingest(game(LocalDate.of(2026,1,4),"SUSPENDED",3,0)),team("LG"),"STADIUM");
        viewing(user,ingest(game(LocalDate.of(2026,1,5),"FINISHED",null,0)),team("LG"),"STADIUM");
        var unknown = game(LocalDate.of(2026,1,6),"FINISHED",5,0);
        unknown.remove("gameType");
        viewing(user,ingest(unknown),team("LG"),"STADIUM");
        mvc.perform(get("/api/home/win-rate").with(as(user)))
                .andExpect(jsonPath("$.winRatePercent").value(0.0)).andExpect(jsonPath("$.registeredCount").value(5))
                .andExpect(jsonPath("$.voidCount").value(1)).andExpect(jsonPath("$.pendingCount").value(1))
                .andExpect(jsonPath("$.unknownCount").value(1)).andExpect(jsonPath("$.unclassifiedCount").value(1));
    }

    @Test
    void calendarReturnsEveryDayAndBothDoubleheaderGamesWithPerGameViewing() throws Exception {
        var user = user();
        var first = game(TODAY,"FINISHED",5,3); first.put("gameSequence",1);
        var second = game(TODAY,"FINISHED",1,3); second.put("gameSequence",2); second.put("scheduledAt",TODAY + "T12:30:00Z");
        importer.importMessage(message(List.of(first,second),OBSERVED,true));
        var ids = games.forDate(TODAY);
        viewing(user,ids.getFirst().id(),team("LG"),"STADIUM");
        viewing(user,ids.getLast().id(),team("LG"),"HOME");
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2026").param("month","9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.today").value("2026-09-14"))
                .andExpect(jsonPath("$.scheduleData.state").value("IMPORTED")).andExpect(jsonPath("$.days.length()").value(30))
                .andExpect(jsonPath("$.days[0].games").isEmpty()).andExpect(jsonPath("$.days[13].games.length()").value(2))
                .andExpect(jsonPath("$.days[13].games[0].opponentTeam.teamCode").value("OB"))
                .andExpect(jsonPath("$.days[13].games[0].displayResult").value("WIN"))
                .andExpect(jsonPath("$.days[13].games[1].displayResult").value("LOSS"))
                .andExpect(jsonPath("$.days[13].games[1].myViewing.viewingType").value("HOME"))
                .andExpect(jsonPath("$.days[13].games[1].recordingState").value("UNAVAILABLE"));
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.registeredCount").value(1));
        mvc.perform(get("/api/games").with(as(user)).param("date",TODAY.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.games.length()").value(2));
    }

    @Test
    void changingFavoriteTeamPreservesTheAwayCheeringResultAndOldCalendarRecord() throws Exception {
        var user = user();
        long game = ingest(game(TODAY,"FINISHED",1,3));
        viewing(user,game,team("OB"),"STADIUM");
        user.updateFavoriteTeam(teams.findByTeamCode("NC").orElseThrow()); users.saveAndFlush(user);
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(jsonPath("$.wins").value(1));
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2026").param("month","9"))
                .andExpect(jsonPath("$.days[13].games[0].displayTeam.teamCode").value("OB"))
                .andExpect(jsonPath("$.days[13].games[0].displayResult").value("WIN"));
    }

    @Test
    void registrationRejectsWrongDateTeamCancelledGameAndForeignOwner() throws Exception {
        var user = user(); var other = user();
        long previous = ingest(game(TODAY.minusDays(1),"FINISHED",5,3));
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(registration(previous,"STADIUM")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RECORDING_DATE_NOT_TODAY"));
        long game = ingest(game(TODAY,"CANCELED",null,null));
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(registration(game,"STADIUM")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GAME_NOT_RECORDABLE"));
        long id = viewing(user,game,team("LG"),"STADIUM");
        mvc.perform(get("/api/user-game-logs/{id}",id).with(as(other))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/user-game-logs/{id}",id).with(as(other)).contentType(MediaType.APPLICATION_JSON).content("{\"viewingType\":\"HOME\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/user-game-logs/{id}",id).with(as(other))).andExpect(status().isNotFound());
        // A retry of the original registration is safe even after cancellation.
        mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(registration(game,"STADIUM"))).andExpect(status().isOk());
    }

    @Test
    void noImportedScheduleIsDifferentFromAnImportedEmptyMonthAndLeapYearWorks() throws Exception {
        var user = user();
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2024").param("month","2"))
                .andExpect(jsonPath("$.scheduleData.state").value("NOT_IMPORTED")).andExpect(jsonPath("$.days.length()").value(29));
        importer.importMessage(message(List.of(),OBSERVED,true));
        mvc.perform(get("/api/home/calendar").with(as(user)).param("year","2026").param("month","9"))
                .andExpect(jsonPath("$.scheduleData.state").value("IMPORTED")).andExpect(jsonPath("$.days[13].games").isEmpty());
    }

    @Test
    void deletionClosesViewingAccessAndApiContractsAreDocumented() throws Exception {
        var user = user();
        long id = viewing(user,ingest(game(TODAY,"FINISHED",5,3)),team("LG"),"STADIUM");
        deletion.deleteCurrentUser(user.getId().toString());
        assertThat(games.logById(user.getId(),id).orElseThrow().deletedAt()).isNotNull();
        mvc.perform(get("/api/home/win-rate").with(as(user))).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/home/calendar'].get.summary").value("홈 월간 달력 조회"))
                .andExpect(jsonPath("$.paths['/api/user-game-logs'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/dev/game-snapshots']").doesNotExist());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentDuplicateRegistrationCreatesOneRow() throws Exception {
        var user = user();
        var input = game(TODAY,"FINISHED",5,3);
        input.put("homeTeam",Map.of("code","KIA")); input.put("awayTeam",Map.of("code","SSG"));
        long game = ingest(input);
        String body = mapper.writeValueAsString(Map.of("gameId",game,"cheeringTeamId",team("HT"),"viewingType","STADIUM"));
        var gate = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Callable<Integer> call = () -> {
                gate.await(5,TimeUnit.SECONDS);
                return mvc.perform(post("/api/user-game-logs").with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getStatus();
            };
            var a = executor.submit(call); var b = executor.submit(call); gate.countDown();
            assertThat(List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,200);
            assertThat(jdbc.queryForObject("select count(*) from user_game_logs where user_id=? and game_id=?",Long.class,user.getId(),game)).isEqualTo(1);
        } finally {
            jdbc.update("delete from user_game_logs where user_id=?",user.getId());
            jdbc.update("delete from game_external_ids where game_id=?",game);
            jdbc.update("delete from games where id=?",game);
            users.deleteById(user.getId());
            jdbc.update("delete from game_sync_scopes where scope_key=?","DAY:"+TODAY);
        }
    }
}
