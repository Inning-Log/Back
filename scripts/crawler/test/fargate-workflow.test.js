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

test("no-game day removes the game task and never polls or sleeps in a window", async () => {
  const state = new MemoryStateStore();
  const publisher = new MemoryPublisher();
  const scheduler = new MemoryScheduleManager();
  const dateKey = "2026-08-26";
  const now = () => Date.parse(`${dateKey}T12:00:00+09:00`);
  await scheduler.upsert(dateKey, now());
  const source = {
    kind: "fixture",
    fetchScheduleMonth: async () => ({ games: [], anomalies: [] }),
    fetchSchedule: async () => ({ games: [], anomalies: [] }),
    fetchScoreboard: async () => { assert.fail("No-game days must not poll scores"); },
    getMetrics: () => ({ logicalRequests: 0, attempts: 0 }),
  };
  const context = { config: config(), source, state, publisher, scheduler, dateKey, now };
  const plan = await runPlanDay(context);
  assert.equal(plan.schedule.action, "deleted");
  assert.equal(scheduler.schedules.size, 0);
  const window = await runGameWindow({ ...context,
    sleep: async () => { assert.fail("No-game windows must exit, not idle"); },
  });
  assert.equal(window.reason, "no-active-games");
  assert.equal(await state.getJson(`LEASE#WINDOW#${dateKey}`), null);
});

test("production cadence waits for the last long-running game and all final checks", async () => {
  const minute = 60_000;
  const start = Date.parse("2026-08-26T18:20:00+09:00");
  let clock = start;
  const scheduledAt = "2026-08-26T18:30:00+09:00";
  const lastFinishedAt = start + 300 * minute;
  const calls = [];
  const state = new MemoryStateStore();
  const result = await runGameWindow({
    config: { polling: {
      planLeadMs: 10 * minute, scoreboardLeadMs: 10 * minute,
      scheduleRefreshMs: 30 * minute, scoreboardRefreshMs: 2 * minute,
      delayedRefreshMs: 5 * minute, leaseRenewMs: 5 * minute,
      finalCheckOffsetsMs: [5, 15, 30].map(x => x * minute),
      hardTimeoutMs: 720 * minute,
    } },
    source: {
      kind: "fixture",
      fetchSchedule: async () => ({ games: [game("SCHEDULED", scheduledAt),
        game("SCHEDULED", scheduledAt, { awayTeam: "KT", homeTeam: "한화",
          externalId: { kbo: "20260826KTHH0", naver: null } })], anomalies: [] }),
      fetchScoreboard: async () => {
        calls.push(clock);
        return { games: [game("FINISHED", scheduledAt),
          game(clock < lastFinishedAt ? "LIVE" : "FINISHED", scheduledAt,
            { awayTeam: "KT", homeTeam: "한화", externalId: { kbo: "20260826KTHH0", naver: null } })], anomalies: [] };
      },
      getMetrics: () => ({ logicalRequests: calls.length, attempts: calls.length }),
    },
    state, publisher: new MemoryPublisher(), dateKey: "2026-08-26",
    now: () => clock, sleep: async ms => { clock += ms; },
  });
  assert.equal(result.reason, "final-checks-complete");
  assert.equal(result.finalChecks, 3);
  assert.equal(clock, lastFinishedAt + 30 * minute);
  assert.equal(calls[1] - calls[0], 2 * minute);
  assert.deepEqual(calls.slice(-3), [5, 15, 30].map(x => lastFinishedAt + x * minute));
  assert.ok((await state.getJson("LATEST#2026-08-26")).games.every(g => g.status === "FINISHED"));
});
