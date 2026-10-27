package com.inninglog.domain.game;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;

@Transactional
class GameScheduleReconciliationTest extends GameIntegrationSupport {
    String complete(List<Map<String,Object>> rows, Instant observed, String... types) {
        var root = mapper.readTree(message(rows, observed, true));
        ((ObjectNode) root.path("payload")).set("scheduleCoverage", mapper.valueToTree(Arrays.stream(types)
                .map(type -> Map.of("gameType",type,"observedAt",observed.toString())).toList()));
        return mapper.writeValueAsString(root);
    }

    @Test void shiftedPostseasonWithdrawsOldFixtureAndPreservesRecords() {
        var old = game(TODAY,"SCHEDULED",null,null); old.put("gameType","PLAYOFF");
        long oldId = ingest(old);
        var user = user(); long logId = viewing(user,oldId,team("LG"),"HOME");
        var replacement = game(TODAY.plusDays(1),"SCHEDULED",null,null); replacement.put("gameType","PLAYOFF");
        importer.importMessage(complete(List.of(replacement),OBSERVED.plusSeconds(1),"PLAYOFF"));
        assertThat(games.forDate(TODAY)).isEmpty();
        assertThat(games.find(oldId)).isPresent();
        assertThat(games.logById(user.getId(),logId)).isPresent();
        assertThat(games.forDate(TODAY.plusDays(1))).hasSize(1);
        // Retries and delayed delivery do not resurrect the obsolete date.
        importer.importMessage(message(List.of(old),OBSERVED.minusSeconds(1),false));
        assertThat(games.forDate(TODAY)).isEmpty();
        importer.importMessage(complete(List.of(old,replacement),OBSERVED.plusSeconds(2),"PLAYOFF"));
        assertThat(games.forDate(TODAY)).extracting(g -> g.id()).containsExactly(oldId);
    }

    @Test void onlyCompleteCoveredSeriesRetiresPregameRows() {
        var post = game(TODAY,"SCHEDULED",null,null); post.put("gameType","PLAYOFF");
        long postId = ingest(post);
        var regular = game(TODAY,"SCHEDULED",null,null); long regularId = ingest(regular);
        var finished = game(TODAY.plusDays(1),"FINISHED",4,2); finished.put("gameType","PLAYOFF");
        long finishedId = ingest(finished);
        var suspended = game(TODAY.plusDays(2),"SUSPENDED",1,0); suspended.put("gameType","PLAYOFF");
        long suspendedId = ingest(suspended);
        var partial = mapper.readTree(complete(List.of(),OBSERVED.plusSeconds(1),"PLAYOFF"));
        ((ObjectNode)partial.path("payload")).set("anomalies",mapper.valueToTree(List.of(Map.of("type","BAD_ROW"))));
        importer.importMessage(mapper.writeValueAsString(partial));
        assertThat(games.scheduleActive(postId)).isTrue();
        importer.importMessage(message(List.of(),OBSERVED.plusSeconds(2),true));
        assertThat(games.scheduleActive(postId)).isTrue();
        importer.importMessage(complete(List.of(),OBSERVED.plusSeconds(3),"PLAYOFF"));
        assertThat(games.scheduleActive(postId)).isFalse();
        assertThat(games.scheduleActive(regularId)).isTrue();
        assertThat(games.scheduleActive(finishedId)).isTrue();
        assertThat(games.scheduleActive(suspendedId)).isTrue();
    }

    @Test void stalePreviouslyUnseenGameCannotReappearAfterAnEmptyCompleteMonth() {
        importer.importMessage(complete(List.of(),OBSERVED.plusSeconds(2),"PLAYOFF"));
        var stale = game(TODAY,"SCHEDULED",null,null); stale.put("gameType","PLAYOFF");
        importer.importMessage(message(List.of(stale),OBSERVED,false));
        assertThat(games.forDate(TODAY)).isEmpty();
        assertThat(games.includingWithdrawnForDate(TODAY)).hasSize(1);
    }

    @Test void cachedCoverageCannotHideFresherGameOrResurrectWithdrawnOne() {
        var old = game(TODAY,"SCHEDULED",null,null); old.put("gameType","PLAYOFF");
        long id = ingest(old);
        importer.importMessage(complete(List.of(),OBSERVED.plusSeconds(2),"PLAYOFF"));
        old.put("meta",Map.of("scheduleObservedAt",OBSERVED.toString(),"resultObservedAt",OBSERVED.toString(),"resultSource","SCHEDULE"));
        importer.importMessage(message(List.of(old),OBSERVED.plusSeconds(3),false));
        assertThat(games.scheduleActive(id)).isFalse();
        old.remove("meta");
        importer.importMessage(message(List.of(old),OBSERVED.plusSeconds(4),false));
        importer.importMessage(complete(List.of(),OBSERVED.plusSeconds(3),"PLAYOFF"));
        assertThat(games.scheduleActive(id)).isTrue();
    }

    @Test void unconfirmedPostseasonStartStillSetsLeagueBoundaryAndClearsOnRemoval() {
        var root = mapper.readTree(complete(List.of(),OBSERVED.plusSeconds(1),"KOREAN_SERIES"));
        ((ObjectNode)root.path("payload")).set("deferredGames",mapper.valueToTree(List.of(Map.of(
                "date",TODAY.toString(),"gameType","KOREAN_SERIES","scheduledAt",TODAY+"T14:00:00+09:00"))));
        importer.importMessage(mapper.writeValueAsString(root));
        assertThat(games.earliestUnconfirmedStart(TODAY)).contains(Instant.parse(TODAY+"T05:00:00Z"));
        assertThat(games.forDate(TODAY)).isEmpty();
        importer.importMessage(complete(List.of(),OBSERVED.plusSeconds(2),"KOREAN_SERIES"));
        assertThat(games.earliestUnconfirmedStart(TODAY)).isEmpty();
    }

    @Test void suspensionWithoutScoresPreservesLastPlayedStateAndPregameCannotUndoIt() {
        var input = game(TODAY,"LIVE",3,1); input.put("inning",6); input.put("half","TOP");
        long id = ingest(input);
        input.put("status","SUSPENDED"); input.put("score",Map.of()); input.remove("inning"); input.remove("half");
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(1),false));
        var suspended = games.find(id).orElseThrow();
        assertThat(suspended.homeScore()).isEqualTo(3);
        assertThat(suspended.awayScore()).isEqualTo(1);
        assertThat(suspended.currentInning()).isEqualTo(6);
        input.put("status","SCHEDULED");
        importer.importMessage(message(List.of(input),OBSERVED.plusSeconds(2),false));
        assertThat(games.find(id).orElseThrow().status()).isEqualTo(com.inninglog.domain.game.model.GameStatus.SUSPENDED);
    }
}
