import assert from "node:assert/strict";
import test from "node:test";
import { loadFargateConfig, evaluateLivePolicy, KBO_ENDPOINTS } from "../src/fargate/config.js";
import { createKboPageSource } from "../src/fargate/source.js";
import { parseKboScheduleResponse, parseKboScoreboardPage } from "../src/fargate/kbo-pages.js";
import { MemoryStateStore, MemoryPublisher } from "../src/fargate/aws.js";
import { runCollectOnce } from "../src/fargate/workflow.js";

const payload = { rows: [{ row: [
  { Class: "day", Text: "09.01(화)", RowSpan: "1" },
  { Class: "time", Text: "<b>18:30</b>" },
  { Class: "play", Text: '<span>LG</span><em><span>3</span><span>vs</span><span>1</span></em><span>두산</span>' },
  { Class: "relay", Text: '<a href="/Schedule/GameCenter/Main.aspx?gameId=20260901LGOB0">리뷰</a>' },
  ...["", "", "", "잠실", "-"].map(Text => ({ Text })),
] }] };
const noGame = '<html><body><div id="cphContents_cphContents_cphContents_udpRecord">2026.09.14(월)<div id="cphContents_cphContents_cphContents_pNoGmae">데이터가 존재하지 않습니다.</div></div></body></html>';

test("live opt-in does not invent KBO approval and still honors kill switch", async () => {
  const { config } = await loadFargateConfig({ profile: "kbo-live", env: {} });
  assert.equal(evaluateLivePolicy(config).ok, false);
  config.policy.killSwitch = false;
  assert.equal(evaluateLivePolicy(config).ok, true);
  assert.equal(config.policy.authorizationStatus, "unverified");
  config.policy.authorizationStatus = "revoked";
  assert.equal(evaluateLivePolicy(config).ok, false);
});

test("actual AJAX cell structure retains scheduled time, scores and game identity", () => {
  const { games, anomalies } = parseKboScheduleResponse(payload, "2026-09");
  assert.equal(anomalies.length, 0);
  assert.equal(games[0].scheduledAt, "2026-09-01T09:30:00.000Z");
  assert.equal(games[0].externalId.kbo, "20260901LGOB0");
  assert.equal(games[0].status, "FINISHED");
  assert.equal(games[0].gameType, "REGULAR");
  assert.deepEqual(games[0].score, { away: 3, home: 1 });
  assert.throws(() => parseKboScheduleResponse("error page", "2026-09"));
  assert.throws(() => parseKboScheduleResponse(payload, "2026-13"));
});

test("actual no-game marker is accepted only in the expected dated scoreboard", () => {
  assert.equal(parseKboScoreboardPage(noGame, "2026-09-14").games.length, 0);
  assert.throws(() => parseKboScoreboardPage(noGame, "2026-09-15"));
  assert.throws(() => parseKboScoreboardPage(noGame.replace('id="cphContents_cphContents_cphContents_pNoGmae"', ''), "2026-09-14"));
});

test("one-shot requests two resources, persists month and empty day, creates no schedule", async () => {
  const { config } = await loadFargateConfig({ profile: "kbo-live", env: { CRAWLER_KILL_SWITCH: "false" } });
  const state = new MemoryStateStore();
  const calls = [];
  let clock = Date.parse("2026-09-14T03:00:00Z");
  const source = createKboPageSource(config, { state, now: () => clock, sleep: async ms => { clock += ms; }, client: {
    post: async (url, body, options) => {
      calls.push(url);
      assert.equal(new URLSearchParams(body).get("gameMonth"), "09");
      assert.equal(options.headers["content-type"], "application/x-www-form-urlencoded; charset=UTF-8");
      return { status: 200, data: JSON.stringify(payload), headers: {} };
    },
    get: async url => { calls.push(url); return { status: 200, data: noGame, headers: {} }; },
  } });
  const result = await runCollectOnce({ config, state, source, publisher: new MemoryPublisher(), dateKey: "2026-09-14", now: () => clock });
  assert.equal(result.monthGameCount, 1);
  assert.equal(result.gameCount, 0);
  assert.equal(result.stateReadBackVerified, true);
  assert.deepEqual(calls, [KBO_ENDPOINTS.scheduleData, KBO_ENDPOINTS.scoreboard]);
  assert.deepEqual(result.requestMetrics, { logicalRequests: 2, attempts: 2 });
  assert.equal((await state.getJson("SCHEDULE#MONTH#2026-09")).source, "kbo-pages");
});
