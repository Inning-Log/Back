import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { loadFargateConfig } from "../src/fargate/config.js";
import { MemoryStateStore } from "../src/fargate/aws.js";
import {
  KboRequestError,
  assertExactEndpoint,
  createKboPageSource,
} from "../src/fargate/source.js";

async function approvedConfig() {
  const loaded = await loadFargateConfig({ profile: "kbo-locked", env: {} });
  const config = structuredClone(loaded.config);
  config.enabled = true;
  config.identity.contact = "mailto:operator@school.ac.kr";
  config.policy = {
    ...config.policy,
    killSwitch: false,
    authorizationStatus: "approved",
    authorizationEvidence: "written-kbo-approval",
    authorizationReviewedAt: "2026-08-25T00:00:00+09:00",
    authorizationExpiresAt: "2026-09-25T00:00:00+09:00",
    authorizationScopes: ["schedule-page", "scoreboard-page", "robots-disallow-override"],
    robotsExceptionGranted: true,
  };
  return config;
}

test("KBO source accepts only the two exact reviewed URLs", () => {
  assert.doesNotThrow(() => assertExactEndpoint("schedule", "https://www.koreabaseball.com/Schedule/Schedule.aspx"));
  for (const url of [
    "https://www.koreabaseball.com/Schedule/Schedule.aspx?date=20260826",
    "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList",
    "https://api-gw.sports.naver.com/schedule/games",
  ]) {
    assert.throws(() => assertExactEndpoint("schedule", url), KboRequestError);
  }
});

test("KBO source applies persistent quotas, bounded spacing, and injected HTML only", async () => {
  const config = await approvedConfig();
  const fixture = await loadFargateConfig({ env: {} });
  const [scheduleHtml, scoreboardHtml] = await Promise.all([
    readFile(fixture.config.fixture.scheduleFile, "utf8"),
    readFile(fixture.config.fixture.scoreboardFile, "utf8"),
  ]);
  const calls = [];
  let clock = Date.parse("2026-08-26T12:00:00+09:00");
  const state = new MemoryStateStore();
  const source = createKboPageSource(config, {
    state,
    now: () => clock,
    sleep: async (ms) => { clock += ms; },
    random: () => 0,
    client: {
      get: async (url) => {
        calls.push(url);
        return {
          status: 200,
          data: url.endsWith("ScoreBoard.aspx") ? scoreboardHtml : scheduleHtml,
          headers: {},
        };
      },
    },
  });
  const schedule = await source.fetchSchedule("2026-08-26");
  const scoreboard = await source.fetchScoreboard("2026-08-26");
  assert.equal(schedule.games.length, 2);
  assert.equal(scoreboard.games.length, 1);
  assert.deepEqual(calls, [config.endpoints.schedule, config.endpoints.scoreboard]);
  assert.equal(source.getMetrics().logicalRequests, 2);
  assert.ok(clock >= Date.parse("2026-08-26T12:00:02+09:00"));
});

test("policy block is checked before an injected HTTP client can run", async () => {
  const config = await approvedConfig();
  config.policy.killSwitch = true;
  let called = false;
  const source = createKboPageSource(config, {
    state: new MemoryStateStore(),
    now: () => Date.parse("2026-08-26T12:00:00+09:00"),
    client: { get: async () => { called = true; } },
  });
  await assert.rejects(source.fetchSchedule("2026-08-26"), (error) => error.code === "FARGATE_POLICY_BLOCKED");
  assert.equal(called, false);
});

test("429 opens a persistent circuit for at least Retry-After and prevents another attempt", async () => {
  const config = await approvedConfig();
  let calls = 0;
  const now = Date.parse("2026-08-26T12:00:00+09:00");
  const state = new MemoryStateStore();
  const source = createKboPageSource(config, {
    state,
    now: () => now,
    client: {
      get: async () => {
        calls += 1;
        return { status: 429, data: "", headers: { "retry-after": "36000" } };
      },
    },
  });
  await assert.rejects(source.fetchSchedule("2026-08-26"), (error) => error.code === "KBO_RATE_LIMITED");
  const circuit = await state.getJson("CIRCUIT#KBO");
  assert.ok(circuit.openUntil >= now + 10 * 60 * 60_000);
  await assert.rejects(source.fetchSchedule("2026-08-26"), (error) => error.code === "KBO_CIRCUIT_OPEN");
  assert.equal(calls, 1);
});
