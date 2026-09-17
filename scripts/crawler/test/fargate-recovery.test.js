import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { getEventListeners } from "node:events";
import { loadFargateConfig } from "../src/fargate/config.js";
import { MemoryStateStore, MemoryPublisher } from "../src/fargate/aws.js";
import { createKboPageSource } from "../src/fargate/source.js";
import { parseKboScoreboardPage, scoreboardEvidence } from "../src/fargate/kbo-pages.js";
import { safeError } from "../src/fargate/diagnostics.js";
import { runGameWindow } from "../src/fargate/workflow.js";
import { abortableSleep } from "../src/fargate/sleep.js";
import { main, resultExitCode } from "../src/fargate/main.js";

async function harness(respond) {
  const { config } = await loadFargateConfig({ profile: "kbo-live", env: { CRAWLER_KILL_SWITCH: "false" } });
  config.scheduleTransport = "html";
  const html = await readFile(config.fixture.scoreboardFile, "utf8");
  const schedule = await readFile(config.fixture.scheduleFile, "utf8");
  const bad = html.replace("6회말", "미확인상태");
  let clock = Date.parse("2026-08-26T18:20:00+09:00");
  const now = () => clock;
  const sleep = async (ms) => { clock += ms; };
  const state = new MemoryStateStore();
  const logs = [], calls = [];
  const source = createKboPageSource(config, { state, now, sleep, log: event => logs.push(event), client: {
    get: async (url, options) => {
      calls.push({ url, headers: options.headers, at: clock });
      return respond({ url, options, html, bad, schedule, calls });
    },
  } });
  return { config, html, bad, now, sleep, state, logs, calls, source };
}
const ok = (data) => ({ status: 200, data, headers: { etag: "test-body" } });

test("all-card validation failure preserves bounded card diagnostics without raw page/errors", async () => {
  const h = await harness(() => {});
  let failure;
  try { parseKboScoreboardPage(h.bad, "2026-08-26"); } catch (e) { failure = e; }
  const log = safeError(failure);
  assert.equal(log.code, "KBO_PAGE_SCHEMA_MISMATCH");
  const card = log.details.anomalies[0];
  assert.equal(card.stage, "status");
  assert.equal(card.away, "NC");
  assert.equal(card.home, "LG");
  assert.equal(card.flag, "미확인상태");
  assert.deepEqual(card.pointScores, ["0", "5"]);
  const protectedLog = safeError({ message: "failed https://user:pass@test.invalid/?token=hidden token=hidden",
    details: { anomalies: Array(100).fill({ message: "x".repeat(900), card: 1, html: h.html }),
      authorization: "hidden", config: { password: "hidden" }, response: h.html } });
  assert.equal(protectedLog.details.anomalies.length, 10);
  assert.equal(protectedLog.details.anomalies[0].message.length, 300);
  assert.doesNotMatch(JSON.stringify(protectedLog), /hidden|doctype|password|user:pass/);
  assert.deepEqual(safeError({ details: [{ path: "request.limit", message: "invalid" }] }).details,
    [{ path: "request.limit", message: "invalid" }]);
});

test("one bad observation recovers after two minutes without caching or opening circuit", async () => {
  const h = await harness(({ calls, bad, html, options }) => {
    if (calls.length === 2) assert.equal(options.headers["if-none-match"], undefined);
    return ok(calls.length === 1 ? bad : html);
  });
  let renewals = 0;
  const result = await h.source.fetchScoreboard("2026-08-26", { beforeRetry: async () => { renewals++; } });
  assert.equal(result.games.length, 1);
  assert.equal(h.calls.length, 2);
  assert.equal(h.calls[1].at - h.calls[0].at, 120_000);
  assert.equal(renewals, 1);
  assert.equal(await h.state.getJson("CIRCUIT#KBO"), null);
  assert.ok(h.logs.some(log => log.event === "schema_recovered"));
  const diagnostic = h.logs.find(log => log.event === "schema_observation_failed");
  assert.match(diagnostic.details.bodySha256, /^[a-f0-9]{64}$/);
  assert.equal(diagnostic.details.page, "scoreboard");
  assert.equal(diagnostic.details.anomalies[0].stage, "status");
  const saved = await h.state.getJson(diagnostic.details.evidenceKey);
  assert.equal(saved.evidence.replayable, true);
  assert.throws(() => parseKboScoreboardPage(saved.evidence.html, "2026-08-26"), error =>
    error.details.anomalies[0].flag === "미확인상태");
  assert.deepEqual(h.source.getMetrics(), { logicalRequests: 2, attempts: 2 });
});

test("failure evidence strips secrets/scripts and preserves parser identity and bounded HTML", async () => {
  const h = await harness(() => {});
  const raw = h.html.replace('<div class="smsScore">', '<div class="smsScore" data-token="secret"><input value="secret"><script>secret</script><!--secret-->')
    .replace("</body>", '<form>secret</form></body>');
  const evidence = scoreboardEvidence(raw);
  assert.doesNotMatch(evidence.html, /secret|onclick|script|input|form/);
  assert.deepEqual(parseKboScoreboardPage(evidence.html, "2026-08-26").games,
    parseKboScoreboardPage(h.html, "2026-08-26").games);
  const oversized = scoreboardEvidence(h.html.replace("6회말", "x".repeat(60_000)));
  assert.equal(oversized.replayable, false);
  assert.equal(oversized.truncated, true);
  assert.ok(Buffer.byteLength(oversized.html) <= 48_000);
  assert.equal(scoreboardEvidence("<html>error</html>").replayable, false);
});

test("persistent parse failure stops after three observations, then circuit blocks all further requests", async () => {
  const h = await harness(({ bad }) => ok(bad));
  await assert.rejects(h.source.fetchScoreboard("2026-08-26"), error =>
    error.code === "KBO_PAGE_SCHEMA_MISMATCH" && error.details.attempt === 3);
  assert.equal(h.calls.length, 3);
  assert.equal(h.calls[2].at - h.calls[0].at, 240_000);
  const circuit = await h.state.getJson("CIRCUIT#KBO");
  assert.equal(circuit.openUntil, h.now() + 6 * 3_600_000);
  await assert.rejects(h.source.fetchScoreboard("2026-08-26"), { code: "KBO_CIRCUIT_OPEN" });
  assert.equal(h.calls.length, 3);
});

for (const [status, data, code] of [
  [403, "denied", "KBO_ACCESS_DENIED"], [429, "rate limited", "KBO_RATE_LIMITED"],
  [200, "<html>captcha</html>", "KBO_BOT_CHALLENGE"],
]) {
  test(`${code} remains immediate stop, never schema recovery`, async () => {
    const h = await harness(() => ({ status, data, headers: {} }));
    await assert.rejects(h.source.fetchScoreboard("2026-08-26"), { code });
    assert.equal(h.calls.length, 1);
    assert.ok(!h.logs.some(log => log.event === "schema_retry_scheduled"));
  });
}

test("schema retry cannot extend hard deadline or proceed after losing lease", async () => {
  const h = await harness(({ bad }) => ok(bad));
  await assert.rejects(h.source.fetchScoreboard("2026-08-26", { deadline: h.now() + 100_000 }),
    { code: "KBO_RECOVERY_DEADLINE" });
  assert.equal(h.calls.length, 1);
  const other = await harness(({ bad }) => ok(bad));
  await assert.rejects(other.source.fetchScoreboard("2026-08-26", {
    beforeRetry: async () => { throw Object.assign(new Error("lost"), { code: "LEASE_LOST" }); },
  }), { code: "LEASE_LOST" });
  assert.equal(other.calls.length, 1);
});

test("schema retries retain quotas and stop before a request exceeding the budget", async () => {
  const h = await harness(({ bad }) => ok(bad));
  h.config.request.maxLogicalRequestsPerHour = 2;
  await assert.rejects(h.source.fetchScoreboard("2026-08-26"), error => /quota/i.test(error.message));
  assert.equal(h.calls.length, 2);
});

test("zero schema retries is an explicit immediate-stop setting", async () => {
  const h = await harness(({ bad }) => ok(bad));
  h.config.request.schemaRetryCount = 0;
  await assert.rejects(h.source.fetchScoreboard("2026-08-26"), { code: "KBO_PAGE_SCHEMA_MISMATCH" });
  assert.equal(h.calls.length, 1);
});

test("game window survives a bad page, renews lease, publishes valid observations and completes final checks", async () => {
  let scores = 0;
  const h = await harness(({ url, bad, html, schedule }) => {
    if (!url.endsWith("ScoreBoard.aspx")) return ok(schedule);
    scores++;
    return ok(scores === 2 ? bad : scores === 1 ? html : html.replace("6회말", "경기종료"));
  });
  const publisher = new MemoryPublisher();
  const result = await runGameWindow({ ...h, publisher, dateKey: "2026-08-26", owner: "integration",
    log: event => h.logs.push(event) });
  assert.equal(result.reason, "final-checks-complete");
  assert.equal(result.finalChecks, 3);
  assert.equal(await h.state.getJson("CIRCUIT#KBO"), null);
  assert.ok(h.logs.some(log => log.event === "schema_recovered"));
  assert.ok(h.logs.filter(log => log.event === "snapshot_saved").length >= 5);
  assert.ok(publisher.messages.every(message => message.anomalies.length === 0));
  assert.equal((await h.state.getJson("LATEST#2026-08-26")).games.find(game => game.awayTeam.code === "NC").status, "FINISHED");
});

test("schema retry settings reject unbounded counts and too-fast retries", async () => {
  const loaded = await loadFargateConfig({ env: { CRAWLER_SCHEMA_RETRY_COUNT: "1", CRAWLER_SCHEMA_RETRY_MINUTES: "2" } });
  assert.equal(loaded.config.request.schemaRetryCount, 1);
  for (const env of [{ CRAWLER_SCHEMA_RETRY_COUNT: "3" }, { CRAWLER_SCHEMA_RETRY_MINUTES: "0.5" }, { CRAWLER_SCHEMA_RETRY_MINUTES: "6" }]) {
    await assert.rejects(loadFargateConfig({ env }), { code: "FARGATE_CONFIG_INVALID" });
  }
});

test("failed response fingerprint and diagnostics differ for score mismatch and scheduled-time failures", async () => {
  const h = await harness(() => {});
  for (const [body, stage] of [
    [h.html.replace('<td class="point">5</td>', '<td class="point">6</td>'), "scores"],
    [h.html.replace("18:30", "미정"), "scheduled-time"],
    [h.html.replace('<strong class="teamT">NC</strong>', '<strong class="teamT">새팀</strong>'), "teams"],
  ]) {
    assert.throws(() => parseKboScoreboardPage(body, "2026-08-26"), error =>
      safeError(error).details.anomalies[0].stage === stage);
  }
});

test("application errors keep run correlation and nested details; hard timeout is a failed exit", async () => {
  const logs = [];
  const failure = Object.assign(new Error("Every KBO scoreboard card failed validation"), {
    code: "KBO_PAGE_SCHEMA_MISMATCH", details: { anomalies: [{ card: 0, message: "unknown state" }] },
  });
  await assert.rejects(main(["--collect-once", "--profile", "fixture", "--date", "2026-08-26"], {}, {
    now: () => Date.parse("2026-08-26T09:00:00Z"), log: event => logs.push(event),
    source: { fetchScheduleMonth: async () => { throw failure; }, getMetrics: () => ({ attempts: 1 }) },
  }), { code: "KBO_PAGE_SCHEMA_MISMATCH" });
  assert.equal(logs[0].event, "run_started");
  assert.equal(logs[1].event, "run_failed");
  assert.equal(logs[0].runId, logs[1].runId);
  assert.equal(logs[1].timestamp, "2026-08-26T09:00:00.000Z");
  assert.equal(logs[1].details.anomalies[0].card, 0);
  assert.equal(resultExitCode({ reason: "hard-timeout" }), 1);
  assert.equal(resultExitCode({ reason: "final-checks-complete" }), 0);
  assert.equal(resultExitCode({ action: "check-policy", ok: false }), 2);
});

test("long-running sleeps release abort listeners and can be cancelled promptly", async () => {
  const controller = new AbortController();
  for (let i = 0; i < 20; i++) await abortableSleep(1, controller.signal);
  assert.equal(getEventListeners(controller.signal, "abort").length, 0);
  const pending = abortableSleep(120_000, controller.signal);
  controller.abort();
  await assert.rejects(pending, { name: "AbortError" });
  await assert.rejects(abortableSleep(120_000, controller.signal), { name: "AbortError" });
  assert.equal(getEventListeners(controller.signal, "abort").length, 0);
});

test("partial invalid cards cannot count toward successful terminal final checks", async () => {
  let scores = 0;
  const h = await harness(({ url, html, schedule }) => {
    if (!url.endsWith("ScoreBoard.aspx")) return ok(schedule);
    scores++;
    const finished = html.replace("6회말", "경기종료");
    // One valid final card plus an invalid card; never accept this as all verified final.
    return ok(finished.replace('</body>', '<div></div></body>')
      .replace('<div class="smsScore">', '<div class="smsScore"><span>broken</span></div><div class="smsScore">'));
  });
  h.config.polling.hardTimeoutMs = 30 * 60_000;
  const result = await runGameWindow({ ...h, publisher: new MemoryPublisher(), dateKey: "2026-08-26" });
  assert.equal(result.reason, "hard-timeout");
  assert.equal(result.finalChecks, 0);
  assert.ok(scores > 3);
});
