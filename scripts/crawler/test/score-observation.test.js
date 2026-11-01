import assert from "node:assert/strict";
import test from "node:test";
import { attachPreviousScoreObservation } from "../src/fargate/score-observation.js";
import { MemoryPublisher, MemoryStateStore, SnapshotPublisher } from "../src/fargate/aws.js";
import { runGameWindow } from "../src/fargate/workflow.js";

function game(seconds, score = 1, overrides = {}) {
  return { date: "2026-11-01", externalId: { kbo: "20261101HTSS0" },
    homeTeam: { code: "SS" }, awayTeam: { code: "KIA" }, status: "LIVE", inning: 2, half: "BOTTOM",
    score: { home: score, away: 0 }, scheduledAt: "2026-11-01T05:00:00Z",
    meta: { resultSource: "SCOREBOARD", resultObservedAt: new Date(Date.UTC(2026, 10, 1, 5, 0, seconds)).toISOString() },
    ...overrides };
}
function snapshot(g) {
  return { schemaVersion: 1, date: g.date, mode: "game-window", observedAt: g.meta.resultObservedAt, games: [g], anomalies: [] };
}

test("last unchanged observation bounds a score change without publishing every poll", async () => {
  const state = new MemoryStateStore();
  const sent = [];
  const publisher = new SnapshotPublisher({ aws: { region: "ap-northeast-2", snapshotQueueUrl: "test" } }, state,
    { sqsClient: { send: async command => { sent.push(JSON.parse(command.input.MessageBody)); return {}; } } });
  let previous;
  for (const [seconds, score] of [[0,1],[15,1],[30,1],[45,3]]) {
    const current = attachPreviousScoreObservation(snapshot(game(seconds,score)),previous);
    await publisher.publishIfChanged(current);
    previous = current;
  }
  assert.equal(sent.length,2);
  assert.deepEqual(sent[1].payload.games[0].meta.previousScoreObservation,
    { observedAt: "2026-11-01T05:00:30.000Z", homeScore: 1, awayScore: 0 });
});

test("missing, cached, ambiguous, other-game and schedule observations never invent a bound", () => {
  const current = snapshot(game(30,3));
  const alternatives = [undefined, { games: [game(30)] }, { games: [game(45)] },
    { games: [game(15),game(15)] }, { games: [game(15,1,{ externalId: { kbo: "another-game" } })] },
    { games: [game(15,1,{ date: "2026-10-31" })] },
    { games: [game(15,1,{ score: { home: null, away: null } })] },
    { games: [game(15,1,{ meta: { resultSource: "SCHEDULE", resultObservedAt: "2026-11-01T05:00:15Z" } })] }];
  for (const previous of alternatives) {
    const result = attachPreviousScoreObservation(structuredClone(current),previous);
    assert.equal(result.games[0].meta.previousScoreObservation,undefined);
  }
});

test("game loop reuses polls and retains a 4 to 3 to 4 source correction", async () => {
  const start = Date.parse("2026-11-01T05:00:00Z");
  let now = start, calls = 0;
  const publisher = new MemoryPublisher();
  const sequence = [3,3,4,3,4,4];
  const result = await runGameWindow({
    config: { polling: { scoreboardLeadMs: 0, scoreboardRefreshMs: 30_000, liveScoreboardRefreshMs: 15_000,
      delayedRefreshMs: 60_000, scheduleRefreshMs: 1_800_000, leaseRenewMs: 300_000,
      finalCheckOffsetsMs: [5_000,10_000,15_000], hardTimeoutMs: 100_000 } },
    dateKey: "2026-11-01", state: new MemoryStateStore(), publisher, now: () => now,
    sleep: async ms => { now += ms; },
    source: { kind: "fixture", getMetrics: () => ({ logicalRequests: calls, attempts: calls }),
      fetchSchedule: async () => ({ games: [game(0,0,{ status: "SCHEDULED", meta: { resultSource: "SCHEDULE" } })], anomalies: [] }),
      fetchScoreboard: async () => ({ games: [game((now-start)/1000,sequence[Math.min(calls++,5)],
        { status: calls >= 6 ? "FINISHED" : "LIVE" })], anomalies: [] }) },
  });
  assert.equal(result.reason,"final-checks-complete");
  const changes = publisher.messages.filter(m => m.games[0].meta.previousScoreObservation)
    .map(m => [m.games[0].meta.previousScoreObservation.homeScore,m.games[0].score.home]);
  assert.deepEqual(changes,[[3,4],[4,3],[3,4],[4,4]]);
});
