import assert from "node:assert/strict";
import test from "node:test";
import { normalizeGameSnapshot } from "../src/domain.js";
import { MemoryPublisher, MemoryScheduleManager, MemoryStateStore } from "../src/fargate/aws.js";
import { runGameWindow, runPlanDay } from "../src/fargate/workflow.js";

function game(status, scheduledAt, overrides = {}) {
  return normalizeGameSnapshot({
    date: "2026-08-26",
    source: { kbo: true, naver: false },
    externalId: { kbo: "20260826NCLG0", naver: null },
    scheduledAt,
    awayTeam: "NC",
    homeTeam: "LG",
    stadium: "잠실",
    status,
    statusText: status,
    score: status === "LIVE" || status === "FINISHED" ? { away: 0, home: 1 } : { away: null, home: null },
    inning: status === "LIVE" ? 3 : null,
    half: status === "LIVE" ? "TOP" : null,
    events: [],
    ...overrides,
  }, { source: "kbo" });
}

function config() {
  return {
    polling: {
      planLeadMs: 60 * 60_000,
      scoreboardLeadMs: 10 * 60_000,
      scheduleRefreshMs: 1_000,
      scoreboardRefreshMs: 10,
      delayedRefreshMs: 20,
      leaseRenewMs: 1_000,
      finalCheckOffsetsMs: [5, 10, 15],
      hardTimeoutMs: 100,
    },
  };
}

test("daily planner publishes schedule and creates exactly one earliest-game task", async () => {
  const state = new MemoryStateStore();
  const publisher = new MemoryPublisher();
  const scheduler = new MemoryScheduleManager();
  const current = Date.parse("2026-08-26T06:00:00+09:00");
  const scheduledAt = "2026-08-26T18:30:00+09:00";
  const result = await runPlanDay({
    config: config(),
    source: {
      kind: "fixture",
      fetchScheduleMonth: async () => ({
        games: [
          game("SCHEDULED", scheduledAt),
          game("SCHEDULED", "2026-08-27T18:30:00+09:00", {
            date: "2026-08-27",
            externalId: { kbo: "20260827LTHT0", naver: null },
            awayTeam: "롯데",
            homeTeam: "KIA",
          }),
        ],
        anomalies: [],
      }),
      getMetrics: () => ({ logicalRequests: 0, attempts: 0 }),
    },
    state,
    publisher,
    scheduler,
    dateKey: "2026-08-26",
    now: () => current,
    owner: "planner-1",
  });
  assert.equal(result.schedule.action, "upserted");
  assert.equal(scheduler.schedules.get("2026-08-26"), "2026-08-26T08:30:00.000Z");
  assert.equal(result.month, "2026-08");
  assert.equal(result.monthGameCount, 2);
  assert.equal((await state.getJson("SCHEDULE#MONTH#2026-08")).games.length, 2);
  assert.equal(publisher.messages.length, 2);

  await state.acquireLease("LEASE#PLAN#2026-08-26", "other", Math.floor(current / 1000) + 600, Math.floor(current / 1000));
  const duplicate = await runPlanDay({
    config: config(), source: {}, state, publisher, scheduler, dateKey: "2026-08-26", now: () => current,
  });
  assert.equal(duplicate.reason, "lease-held");
});

test("game window uses live cadence and performs 5/30/90-style final checks before exit", async () => {
  let clock = Date.parse("2026-08-26T18:20:00+09:00");
  const scheduledAt = "2026-08-26T18:30:00+09:00";
  let scoreboardCalls = 0;
  const source = {
    kind: "fixture",
    fetchSchedule: async () => ({ games: [game("SCHEDULED", scheduledAt)], anomalies: [] }),
    fetchScoreboard: async () => {
      scoreboardCalls += 1;
      return {
        games: [game(scoreboardCalls === 1 ? "LIVE" : "FINISHED", scheduledAt)],
        anomalies: [],
      };
    },
    getMetrics: () => ({ logicalRequests: 0, attempts: 0 }),
  };
  const result = await runGameWindow({
    config: config(),
    source,
    state: new MemoryStateStore(),
    publisher: new MemoryPublisher(),
    dateKey: "2026-08-26",
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
    owner: "window-1",
  });
  assert.equal(result.reason, "final-checks-complete");
  assert.equal(result.finalChecks, 3);
  assert.equal(scoreboardCalls, 5);
});

test("a schedule cancellation before scoreboard start exits without a scoreboard request", async () => {
  let clock = Date.parse("2026-08-26T18:00:00+09:00");
  const scheduledAt = "2026-08-26T18:30:00+09:00";
  let scheduleCalls = 0;
  let scoreboardCalls = 0;
  const polling = {
    ...config().polling,
    scoreboardLeadMs: 0,
    scheduleRefreshMs: 10,
    hardTimeoutMs: 100,
  };
  const result = await runGameWindow({
    config: { polling },
    source: {
      kind: "fixture",
      fetchSchedule: async () => {
        scheduleCalls += 1;
        return {
          games: [game(scheduleCalls === 1 ? "SCHEDULED" : "CANCELLED", scheduledAt)],
          anomalies: [],
        };
      },
      fetchScoreboard: async () => {
        scoreboardCalls += 1;
        return { games: [], anomalies: [] };
      },
      getMetrics: () => ({ logicalRequests: 0, attempts: 0 }),
    },
    state: new MemoryStateStore(),
    publisher: new MemoryPublisher(),
    dateKey: "2026-08-26",
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
    owner: "window-cancel",
  });
  assert.equal(result.reason, "all-games-cancelled-or-postponed");
  assert.equal(scoreboardCalls, 0);
});
