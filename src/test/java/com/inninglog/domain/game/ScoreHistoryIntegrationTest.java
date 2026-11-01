package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.game.ingestion.GameImportException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class ScoreHistoryIntegrationTest extends GameIntegrationSupport {
    @Test
    void returnsLastUnchangedObservationAndSeparatesSourceCorrectionsWithoutGrowingUnchangedHistory() throws Exception {
        var owner = user();
        var entry = game(TODAY,"LIVE",1,0);
        entry.put("inning",2); entry.put("half","BOTTOM");
        observe(entry,120,1,null);
        long id = id(entry);
        observe(entry,105,1,evidence(120,1));
        assertThat(games.stateHistory(id)).hasSize(1);
        observe(entry,90,3,evidence(105,1));
        entry.put("inning",3); entry.put("half","TOP");
        observe(entry,75,3,evidence(90,3));
        observe(entry,60,4,evidence(75,3));
        observe(entry,45,3,evidence(60,4));
        observe(entry,30,4,evidence(45,3));
        // Late delivery cannot roll the accepted history back.
        observe(entry,100,2,evidence(110,1));
        assertThat(games.stateHistory(id)).hasSize(6);
        mvc.perform(get("/api/games/{id}/score-changes",id).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timeBasis").value("OBSERVED"))
                .andExpect(jsonPath("$.actualEventTimeAvailable").value(false))
                .andExpect(jsonPath("$.automaticCorrectionAvailable").value(false))
                .andExpect(jsonPath("$.changes.length()").value(5))
                .andExpect(jsonPath("$.changes[0].type").value("INITIAL_OBSERVATION"))
                .andExpect(jsonPath("$.changes[0].before").isEmpty())
                .andExpect(jsonPath("$.changes[1].type").value("SCORE_INCREASE"))
                .andExpect(jsonPath("$.changes[1].before.homeScore").value(1))
                .andExpect(jsonPath("$.changes[1].after.homeScore").value(3))
                .andExpect(jsonPath("$.changes[1].previousScoreObservedAt").value(NOW.minusSeconds(105).toString()))
                .andExpect(jsonPath("$.changes[1].observationGapMillis").value(15000))
                .andExpect(jsonPath("$.changes[1].occurredAt").isEmpty())
                .andExpect(jsonPath("$.changes[3].type").value("SCORE_CORRECTION"))
                .andExpect(jsonPath("$.changes[4].type").value("SCORE_INCREASE"));
        mvc.perform(get("/api/games/{id}/states",id).with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.states.length()").value(6));
    }

    @Test
    void oldOrDiscontinuousHistoryDoesNotFabricateAPollingWindow() throws Exception {
        var owner = user(); var entry = game(TODAY,"LIVE",1,0);
        observe(entry,120,1,null); long id = id(entry);
        observe(entry,90,2,null);
        observe(entry,60,3,evidence(75,99)); // prior poll does not agree with retained history
        observe(entry,45,null,null);
        observe(entry,30,4,null);
        var response = mapper.readTree(mvc.perform(get("/api/games/{id}/score-changes",id).with(as(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(response.path("changes").size()).isEqualTo(4);
        for (var change : response.path("changes")) {
            assertThat(change.path("previousScoreObservedAt").isNull()).isTrue();
            assertThat(change.path("observationGapMillis").isNull()).isTrue();
        }
        assertThat(response.path("changes").get(3).path("type").stringValue()).isEqualTo("BASELINE_AFTER_GAP");
    }

    @Test
    void rejectsInvalidEvidenceAndProtectsTheReadEndpoint() throws Exception {
        var owner = user(); var entry = game(TODAY,"LIVE",1,0);
        observe(entry,120,1,null); long id = id(entry);
        assertThatThrownBy(() -> observe(entry,90,2,evidence(80,1))).isInstanceOf(GameImportException.class);
        assertThatThrownBy(() -> observe(entry,90,2,evidence(100,-1))).isInstanceOf(GameImportException.class);
        assertThat(games.stateHistory(id)).hasSize(1);
        mvc.perform(get("/api/games/{id}/score-changes",id)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/games/0/score-changes").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/games/9223372036854775807/score-changes").with(as(owner))).andExpect(status().isNotFound());
    }

    private Map<String,Object> evidence(long secondsAgo, int home) {
        return Map.of("observedAt",NOW.minusSeconds(secondsAgo).toString(),"homeScore",home,"awayScore",0);
    }
    private void observe(Map<String,Object> entry, long secondsAgo, Integer home, Map<String,Object> evidence) {
        Instant at = NOW.minusSeconds(secondsAgo);
        var score = new java.util.LinkedHashMap<String,Object>(); score.put("home",home); score.put("away",home == null ? null : 0);
        entry.put("score",score);
        var meta = new java.util.LinkedHashMap<String,Object>();
        meta.put("scheduleObservedAt",at.toString()); meta.put("resultObservedAt",at.toString()); meta.put("resultSource","SCOREBOARD");
        if (evidence != null) meta.put("previousScoreObservation",evidence);
        entry.put("meta",meta);
        importer.importMessage(message(List.of(entry),at,false));
    }
    private long id(Map<String,Object> entry) {
        return jdbc.queryForObject("select game_id from game_external_ids where external_id = ?",Long.class,
                ((Map<?,?>)entry.get("externalId")).get("kbo"));
    }
}
