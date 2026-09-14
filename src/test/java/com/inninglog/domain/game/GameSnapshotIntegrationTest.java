package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.*;
import com.inninglog.domain.game.ingestion.GameImportException;
import com.inninglog.domain.game.model.GameStatus;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class GameSnapshotIntegrationTest extends GameIntegrationSupport {
    @Test
    void realCrawlerPublisherFixturesRoundTripIntoOneSetOfDatabaseGames() throws Exception {
        try (var month = getClass().getResourceAsStream("/game-snapshots/crawler-month.json");
             var window = getClass().getResourceAsStream("/game-snapshots/crawler-game-window.json")) {
            importer.importMessage(new String(month.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            long id = jdbc.queryForObject("select game_id from game_external_ids where external_id='20260826NCLG0'",Long.class);
            importer.importMessage(new String(window.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            var game = games.find(id).orElseThrow();
            assertThat(game.status()).isEqualTo(GameStatus.LIVE);
            assertThat(game.homeScore()).isEqualTo(5);
            assertThat(game.awayTeam().teamCode()).isEqualTo("NC");
            assertThat(game.resultSource()).isEqualTo("SCOREBOARD");
            assertThat(games.forDate(java.time.LocalDate.of(2026,8,26))).hasSize(2);
        }
    }
    @Test
    void duplicateReverseDeliveryAndAtoBtoACorrectionKeepOneGame() {
        var input = game(TODAY,"FINISHED",5,3);
        String a = message(List.of(input),OBSERVED,false);
        long id = ingest(input);
        assertThat(importer.importMessage(a).duplicate()).isTrue();
        input.put("score",Map.of("home",1,"away",3));
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(1),false));
        assertThat(games.find(id).orElseThrow().homeScore()).isEqualTo(1);
        importer.importMessage(a); // old receipt
        assertThat(games.find(id).orElseThrow().homeScore()).isEqualTo(1);
        input.put("score",Map.of("home",5,"away",3));
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(2),false));
        assertThat(games.find(id).orElseThrow().homeScore()).isEqualTo(5);
        input.put("score",Map.of("home",9,"away",3));
        importer.importMessage(message(List.of(input),OBSERVED.minusSeconds(5),false));
        assertThat(games.find(id).orElseThrow().homeScore()).isEqualTo(5);
    }

    @Test
    void latePregameScheduleDoesNotEraseLiveOrFinishedResult() {
        var input = game(TODAY,"FINISHED",5,3);
        long id = ingest(input);
        input.put("status","SCHEDULED"); input.put("score",Map.of());
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(1),true));
        var saved = games.find(id).orElseThrow();
        assertThat(saved.status()).isEqualTo(GameStatus.FINISHED);
        assertThat(saved.homeScore()).isEqualTo(5);
        assertThat(saved.resultObservedAt()).isEqualTo(OBSERVED);
    }

    @Test
    void provisionalScheduleGainsExternalIdWithoutLosingViewingReference() {
        var input = game(TODAY,"SCHEDULED",null,null);
        var external = input.remove("externalId"); input.put("gameSequence",null);
        importer.importMessage(message(List.of(input),OBSERVED,false));
        long id = games.forDate(TODAY).getFirst().id();
        var user = user();
        long logId = viewing(user,id,team("LG"),"STADIUM");
        input.put("externalId",external); input.put("gameSequence",0); input.put("status","FINISHED"); input.put("score",Map.of("home",5,"away",3));
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(1),false));
        assertThat(games.forDate(TODAY)).hasSize(1);
        assertThat(games.logById(user.getId(),logId).orElseThrow().gameId()).isEqualTo(id);
        assertThat(games.find(id).orElseThrow().gameSequence()).isZero();
    }

    @Test
    void missingRowsAreNotCancellationAndPartialMonthRemainsVisible() {
        var input = game(TODAY,"SCHEDULED",null,null); long id = ingest(input);
        var envelope = mapper.readTree(message(List.of(),OBSERVED.plusSeconds(1),true));
        ((tools.jackson.databind.node.ObjectNode)envelope.path("payload")).set("anomalies",mapper.valueToTree(List.of(Map.of("type","INVALID_SCHEDULE_ROW"))));
        importer.importMessage(mapper.writeValueAsString(envelope));
        assertThat(games.find(id).orElseThrow().status()).isEqualTo(GameStatus.SCHEDULED);
        assertThat(games.syncState("MONTH:2026-09").state()).isEqualTo("PARTIAL");
    }

    @Test
    void currentFieldObservationIsRequiredForMergedGameWindow() {
        var input = game(TODAY,"LIVE",5,3);
        var envelope = mapper.readTree(message(List.of(input),OBSERVED,false));
        ((tools.jackson.databind.node.ObjectNode)envelope.path("payload")).put("mode","game-window");
        assertThatThrownBy(() -> importer.importMessage(mapper.writeValueAsString(envelope)))
                .isInstanceOf(GameImportException.class).hasMessage("MISSING_FIELD_OBSERVATION");
    }

    @Test
    void differentTimeWithoutAStableIdDoesNotGuessWhichGameWasRescheduled() {
        var input = game(TODAY,"SCHEDULED",null,null);
        input.remove("externalId"); input.put("gameSequence",null);
        importer.importMessage(message(List.of(input),OBSERVED,false));
        input.put("scheduledAt",TODAY+"T11:30:00Z");
        assertThatThrownBy(() -> importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(1),false)))
                .isInstanceOf(GameImportException.class).hasMessage("AMBIGUOUS_GAME_IDENTITY");
    }

    @Test
    void newerEnvelopeWithCachedOlderScoreDoesNotUndoANewerCorrection() {
        var input = game(TODAY,"FINISHED",5,3);
        long id = ingest(input);
        input.put("score",Map.of("home",8,"away",3));
        input.put("meta",Map.of("scheduleObservedAt",OBSERVED.plusSeconds(5).toString(),
                "resultObservedAt",OBSERVED.minusSeconds(10).toString(),"resultSource","SCOREBOARD"));
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(5),false));
        assertThat(games.find(id).orElseThrow().homeScore()).isEqualTo(5);
    }

    @Test
    void negativeScoreIsRejectedBeforeWriting() {
        var input = game(TODAY,"FINISHED",-1,3);
        assertThatThrownBy(() -> importer.importMessage(message(List.of(input),OBSERVED,false)))
                .isInstanceOf(GameImportException.class).hasMessage("NEGATIVE_SCORE");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void identityConflictRollsBackTheWholeSnapshotAndItsReceipt() {
        long before = jdbc.queryForObject("select count(*) from games",Long.class);
        var a = game(TODAY.minusDays(50),"SCHEDULED",null,null);
        var b = new LinkedHashMap<>(a); b.put("awayTeam",Map.of("code","NC"));
        assertThatThrownBy(() -> importer.importMessage(message(List.of(a,b),OBSERVED,false)))
                .isInstanceOf(GameImportException.class).hasMessage("GAME_TEAMS_CHANGED");
        assertThat(jdbc.queryForObject("select count(*) from games",Long.class)).isEqualTo(before);
    }
}
