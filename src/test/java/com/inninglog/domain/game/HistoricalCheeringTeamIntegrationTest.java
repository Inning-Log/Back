package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.inninglog.domain.user.entity.User;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Transactional
class HistoricalCheeringTeamIntegrationTest extends GameIntegrationSupport {
    @ParameterizedTest
    @CsvSource({
            "2025,REGULAR,LG,5,3,WIN",
            "2026,REGULAR,LG,1,3,LOSS",
            "2025,REGULAR,OB,1,3,WIN",
            "2026,REGULAR,OB,5,3,LOSS",
            "2025,KOREAN_SERIES,LG,5,3,WIN",
            "2026,KOREAN_SERIES,LG,1,3,LOSS",
            "2025,KOREAN_SERIES,OB,1,3,WIN",
            "2026,KOREAN_SERIES,OB,5,3,LOSS",
            "2025,REGULAR,LG,3,3,DRAW",
            "2026,REGULAR,OB,3,3,DRAW"
    })
    void recordedResultsAndRatesSurviveRepeatedFavoriteTeamChanges(int year, String gameType,
            String cheeringCode, int homeScore, int awayScore, String result) throws Exception {
        var viewer = user();
        var date = LocalDate.of(year, 9, 14);
        var input = game(date, "FINISHED", homeScore, awayScore);
        input.put("gameType", gameType);
        long gameId = ingest(input);
        long cheeringTeamId = team(cheeringCode);
        long logId = viewing(viewer, gameId, cheeringTeamId, "STADIUM");
        String opponentCode = "LG".equals(cheeringCode) ? "OB" : "LG";

        JsonNode beforeGame = calendarGame(viewer, year);
        assertThat(beforeGame.path("displayTeam").path("id").asLong()).isEqualTo(cheeringTeamId);
        assertThat(beforeGame.path("opponentTeam").path("id").asLong()).isEqualTo(team(opponentCode));
        assertThat(beforeGame.path("displayResult").asText()).isEqualTo(result);
        JsonNode beforeRate = winRate(viewer, year);
        assertThat(beforeRate.path("registeredCount").asInt()).isEqualTo(1);
        assertThat(beforeRate.path("wins").asInt()).isEqualTo("WIN".equals(result) ? 1 : 0);
        assertThat(beforeRate.path("losses").asInt()).isEqualTo("LOSS".equals(result) ? 1 : 0);
        assertThat(beforeRate.path("draws").asInt()).isEqualTo("DRAW".equals(result) ? 1 : 0);

        for (String nextTeam : List.of(opponentCode, "NC", cheeringCode)) {
            changeFavoriteTeam(viewer, nextTeam);
            assertThat(calendarGame(viewer, year)).isEqualTo(beforeGame);
            assertThat(winRate(viewer, year)).isEqualTo(beforeRate);
            String gamesBody = mvc.perform(get("/api/games").with(as(viewer)).param("date", date.toString()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(gamesBody).path("games").get(0)).isEqualTo(beforeGame);
            String timelineBody = mvc.perform(get("/api/timelines/me").with(as(viewer))
                            .param("gameId", Long.toString(gameId)))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(mapper.readTree(timelineBody).path("game")).isEqualTo(beforeGame);
            assertThat(games.logById(viewer.getId(), logId).orElseThrow().cheeringTeamId()).isEqualTo(cheeringTeamId);
        }
    }

    @Test
    void gamesWithoutActiveViewingStillUseCurrentFavoriteTeam() throws Exception {
        var viewer = user();
        long gameId = ingest(game(TODAY, "FINISHED", 5, 3));
        assertThat(calendarGame(viewer, 2026).path("displayResult").asText()).isEqualTo("WIN");
        changeFavoriteTeam(viewer, "OB");
        assertThat(calendarGame(viewer, 2026).path("displayResult").asText()).isEqualTo("LOSS");

        long logId = viewing(viewer, gameId, team("LG"), "HOME");
        assertThat(calendarGame(viewer, 2026).path("displayResult").asText()).isEqualTo("WIN");
        mvc.perform(delete("/api/user-game-logs/{id}", logId).with(as(viewer))).andExpect(status().isNoContent());
        var response = calendarGame(viewer, 2026);
        assertThat(response.path("displayResult").asText()).isEqualTo("LOSS");
        assertThat(response.path("myViewing").isNull()).isTrue();
        assertThat(response.path("recordingState").asText()).isEqualTo("NONE");

        changeFavoriteTeam(viewer, "NC");
        String body = mvc.perform(get("/api/games").with(as(viewer)).param("date", TODAY.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var unregistered = mapper.readTree(body).path("games").get(0);
        assertThat(unregistered.path("displayTeam").isNull()).isTrue();
        assertThat(unregistered.path("displayResult").asText()).isEqualTo("UNKNOWN");
    }

    private void changeFavoriteTeam(User viewer, String code) throws Exception {
        mvc.perform(put("/api/mypage/favorite-team").with(as(viewer)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("favoriteTeamId", team(code)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.favoriteTeam.id").value(team(code)));
    }

    private JsonNode calendarGame(User viewer, int year) throws Exception {
        String body = mvc.perform(get("/api/home/calendar").with(as(viewer))
                        .param("year", Integer.toString(year)).param("month", "9"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).path("days").get(13).path("games").get(0);
    }

    private JsonNode winRate(User viewer, int year) throws Exception {
        String body = mvc.perform(get("/api/home/win-rate").with(as(viewer)).param("year", Integer.toString(year)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body);
    }
}
