import assert from "node:assert/strict";
import test from "node:test";

import {
  nextDetailEligibility,
  pollingKey,
  recommendedScheduleInterval,
  recordDetailPoll,
  selectDetailQueue,
  shouldPollSchedule,
} from "../src/scheduler.js";

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;

function config(maxDetails = 2) {
  return {
    run: { maxDetailGamesPerRun: maxDetails },
    strategy: {
      polling: {
        farFutureMs: 6 * HOUR,
        gameDayMs: 15 * MINUTE,
        nearStartMs: MINUTE,
        liveScheduleMs: MINUTE,
        liveDetailMs: 2 * MINUTE,
        delayedDetailMs: 5 * MINUTE,
        finishedOffsetsMs: [5 * MINUTE, 30 * MINUTE, 90 * MINUTE],
      },
    },
  };
}

function game(id, status, overrides = {}) {
  return {
    date: "2026-08-26",
    externalId: { naver: id },
    homeTeam: { code: `H${id}` },
    awayTeam: { code: `A${id}` },
    scheduledAt: "2026-08-26T09:30:00.000Z",
    status,
    ...overrides,
  };
}

test("schedule interval adapts to far-future, near-start, and live states", () => {
  const settings = config();
  const now = Date.parse("2026-08-26T08:00:00.000Z");

  assert.equal(
    recommendedScheduleInterval([], "2026-08-29", settings, now),
    6 * HOUR
  );
  assert.equal(
    recommendedScheduleInterval(
      [game("near", "SCHEDULED", { scheduledAt: "2026-08-26T08:30:00.000Z" })],
      "2026-08-26",
      settings,
      now
    ),
    MINUTE
  );
  assert.equal(
    recommendedScheduleInterval([game("live", "LIVE")], "2026-08-26", settings, now),
    MINUTE
  );
});

test("fallback polling key separates canonical doubleheader game numbers", () => {
  const common = {
    date: "2026-08-26",
    externalId: { kbo: null, naver: null },
    homeTeam: { code: "LG" },
    awayTeam: { code: "DOO" },
    scheduledAt: null,
  };
  assert.notEqual(
    pollingKey({ ...common, gameNumber: 1 }),
    pollingKey({ ...common, gameNumber: 2 })
  );
});

test("persistent lastPolledAt suppresses schedule requests until eligibility", () => {
  const settings = config();
  const last = Date.parse("2026-08-26T00:00:00.000Z");
  const state = {
    snapshots: {
      "2026-08-26": { lastPolledAt: new Date(last).toISOString() },
    },
  };
  const games = [
    game("later", "SCHEDULED", {
      scheduledAt: "2026-08-26T09:30:00.000Z",
    }),
  ];

  assert.equal(
    shouldPollSchedule({
      state,
      dateKey: "2026-08-26",
      previousGames: games,
      config: settings,
      nowMs: last + 14 * MINUTE,
    }),
    false
  );
  assert.equal(
    shouldPollSchedule({
      state,
      dateKey: "2026-08-26",
      previousGames: games,
      config: settings,
      nowMs: last + 16 * MINUTE,
    }),
    true
  );
});

test("detail queue is TTL-gated, fair, bounded, and stops completed records", () => {
  const settings = config(2);
  const now = Date.parse("2026-08-26T12:00:00.000Z");
  const games = [
    game("live-old", "LIVE"),
    game("live-fresh", "LIVE"),
    game("delayed", "DELAYED"),
    game("finished", "FINISHED", {
      endedAt: new Date(now - 10 * MINUTE).toISOString(),
    }),
    game("complete", "FINISHED", {
      endedAt: new Date(now - 2 * HOUR).toISOString(),
    }),
  ];
  const state = {
    polling: {
      "live-old": { lastDetailAt: new Date(now - 3 * MINUTE).toISOString() },
      "live-fresh": { lastDetailAt: new Date(now - MINUTE).toISOString() },
      delayed: { lastDetailAt: new Date(now - 6 * MINUTE).toISOString() },
      finished: { finishedPolls: 0 },
      complete: { recordComplete: true, finishedPolls: 1 },
    },
  };

  const queue = selectDetailQueue(games, state, settings, now);
  assert.equal(queue.length, 2);
  assert.deepEqual(
    queue.map((entry) => entry.key),
    ["finished", "delayed"]
  );
  assert.equal(
    nextDetailEligibility(games[4], state.polling.complete, settings),
    Number.POSITIVE_INFINITY
  );
});

test("recordDetailPoll stores only error code and advances finished count", () => {
  const state = { polling: {} };
  const finished = game("record", "FINISHED");
  recordDetailPoll(state, finished, {
    at: "2026-08-26T12:00:00.000Z",
    error: Object.assign(new Error("secret response text"), {
      code: "PROVIDER_SCHEMA_MISMATCH",
    }),
  });

  assert.equal(state.polling.record.finishedPolls, 1);
  assert.equal(
    state.polling.record.lastError.code,
    "PROVIDER_SCHEMA_MISMATCH"
  );
  assert.doesNotMatch(JSON.stringify(state), /secret response text/);
});
