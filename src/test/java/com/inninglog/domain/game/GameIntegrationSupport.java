package com.inninglog.domain.game;

import com.inninglog.domain.game.ingestion.GameSnapshotImporter;
import com.inninglog.domain.game.repository.GameRepository;
import com.inninglog.domain.team.repository.KboTeamRepository;
import com.inninglog.domain.user.entity.User;
import com.inninglog.domain.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=${GAME_TEST_JDBC_URL:jdbc:h2:mem:game_api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.username=${GAME_TEST_DB_USER:sa}",
        "spring.datasource.password=${GAME_TEST_DB_PASSWORD:}",
        "app.games.allow-fixtures=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(GameIntegrationSupport.TimeConfig.class)
abstract class GameIntegrationSupport {
    // UTC September 13 is already September 14 in Korea.
    static final Instant NOW = Instant.parse("2026-09-13T15:05:00Z");
    static final Instant OBSERVED = NOW.minusSeconds(120);
    static final LocalDate TODAY = LocalDate.of(2026, 9, 14);
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired GameSnapshotImporter importer;
    @Autowired GameRepository games;
    @Autowired UserRepository users;
    @Autowired KboTeamRepository teams;
    @Autowired ObjectMapper mapper;

    @TestConfiguration
    static class TimeConfig {
        @Bean @Primary Clock gameTestClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    User user() {
        String name = "game" + UUID.randomUUID().toString().substring(0, 8);
        User user = new User(name + "@example.com", null);
        user.setupOnboardingUsername(name);
        user.setupOnboardingNickname(name);
        user.selectInitialFavoriteTeam(teams.findByTeamCode("LG").orElseThrow());
        return users.saveAndFlush(user);
    }

    RequestPostProcessor as(User user) {
        return SecurityMockMvcRequestPostProcessors.jwt().jwt(jwt -> jwt.subject(user.getId().toString()));
    }

    long team(String code) { return teams.findByTeamCode(code).orElseThrow().getId(); }

    Map<String,Object> game(LocalDate date, String status, Integer homeScore, Integer awayScore) {
        Map<String,Object> game = new LinkedHashMap<>();
        game.put("date", date.toString());
        game.put("seasonYear", date.getYear());
        game.put("gameType", "REGULAR");
        game.put("gameSequence", 0);
        game.put("scheduledAt", date + "T09:30:00Z");
        game.put("homeTeam", Map.of("code","LG"));
        game.put("awayTeam", Map.of("code","DOO"));
        game.put("stadium", "테스트 구장");
        game.put("status", status);
        Map<String,Object> score = new LinkedHashMap<>();
        score.put("home",homeScore); score.put("away",awayScore);
        game.put("score",score);
        game.put("externalId",Map.of("kbo","test-" + UUID.randomUUID()));
        return game;
    }

    String message(List<Map<String,Object>> entries, Instant observed, boolean monthly) {
        LocalDate date = entries.isEmpty() ? TODAY : LocalDate.parse((String) entries.getFirst().get("date"));
        Map<String,Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion",1);
        payload.put("source","fixture-kbo-pages");
        payload.put("mode",monthly ? "schedule-month" : "plan-day");
        payload.put("date",(monthly ? date.withDayOfMonth(1) : date).toString());
        if (monthly) payload.put("month",date.toString().substring(0,7));
        payload.put("observedAt",observed.toString());
        payload.put("games",entries);
        payload.put("anomalies",List.of());
        return mapper.writeValueAsString(Map.of("schemaVersion",1,
                "type",monthly ? "KBO_SCHEDULE_MONTH_SNAPSHOT" : "KBO_GAME_SNAPSHOT", "payload",payload));
    }

    long ingest(Map<String,Object> game) {
        importer.importMessage(message(List.of(game),OBSERVED,false));
        return jdbc.queryForObject("select game_id from game_external_ids where external_id = ?", Long.class,
                ((Map<?,?>)game.get("externalId")).get("kbo"));
    }

    long viewing(User user, long gameId, long cheeringId, String type) {
        return games.createLog(user.getId(),gameId,cheeringId,
                com.inninglog.domain.game.model.ViewingType.valueOf(type),NOW);
    }

    String registration(long gameId, String type) {
        return mapper.writeValueAsString(Map.of("gameId",gameId,"cheeringTeamId",team("LG"),"viewingType",type));
    }
}
