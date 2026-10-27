import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { load } from "cheerio";
import { parseKboScheduleMonthPage, parseKboSchedulePage, parseKboScoreboardPage } from "../src/fargate/kbo-pages.js";
import { createKboPageSource } from "../src/fargate/source.js";
import { loadFargateConfig } from "../src/fargate/config.js";
import { MemoryStateStore, MemoryPublisher, MemoryScheduleManager } from "../src/fargate/aws.js";
import { runPlanDay, runGameWindow } from "../src/fargate/workflow.js";

// Public table fragments observed in Chrome on 2026-10-23; no cookies or form state.
const october = await readFile(new URL("../fixtures/kbo/postseason-october-2026.html", import.meta.url), "utf8");
const november = await readFile(new URL("../fixtures/kbo/postseason-november-2026.html", import.meta.url), "utf8");
function payload(html) {
  const $ = load(html);
  return { rows: $("#tblScheduleList tbody tr").toArray().map(tr => ({ row: $(tr).children("td").toArray()
    .map(td => ({ Text: $(td).html(), Class: $(td).attr("class") ?? "" })) })) };
}
async function runtime(date, responses = {}) {
  const { config } = await loadFargateConfig({ profile: "kbo-live", env: { CRAWLER_KILL_SWITCH: "false" } });
  let clock = Date.parse(`${date}T06:00:00+09:00`);
  const state = new MemoryStateStore(), publisher = new MemoryPublisher(), scheduler = new MemoryScheduleManager(), calls = [];
  const source = createKboPageSource(config, { state, now: () => clock, sleep: async ms => { clock += ms; }, client: {
    post: async (url, body) => {
      const form = new URLSearchParams(body);
      const key = `${form.get("seasonId")}-${form.get("gameMonth")}/${form.get("srIdList")}`;
      calls.push(key);
      return { status: 200, headers: {}, data: responses[key] ?? { rows: [] } };
    },
    get: async () => { throw new Error("No scoreboard should be fetched without a confirmed game"); },
  } });
  return { config, state, publisher, scheduler, source, calls, dateKey: date, now: () => clock,
    advance: ms => { clock += ms; } };
}

test("no postseason: one daily discovery, no next-month query, no realtime task", async () => {
  const ctx = await runtime("2026-08-20");
  const planned = await runPlanDay(ctx);
  assert.equal(planned.activeGameCount, 0);
  assert.equal(ctx.scheduler.schedules.size, 0);
  assert.deepEqual(ctx.calls, ["2026-08/0,9,6", "2026-08/3,4,5,7"]);
  await ctx.source.fetchSchedule("2026-08-20");
  assert.deepEqual(ctx.calls.slice(2), ["2026-08/0,9,6"]);
  assert.equal((await runGameWindow(ctx)).reason, "no-active-games");
});

test("off-day removes old task, publishes next-month bracket, never starts scoreboard", async () => {
  const ctx = await runtime("2026-10-23", { "2026-10/3,4,5,7": payload(october), "2026-11/3,4,5,7": payload(november) });
  await ctx.scheduler.upsert(ctx.dateKey, "2026-10-23T09:00:00Z");
  const planned = await runPlanDay(ctx);
  assert.equal(planned.monthGameCount, 9);
  assert.equal(ctx.scheduler.schedules.size, 0);
  assert.equal((await ctx.state.getJson("SCHEDULE#MONTH#2026-11")).deferredGames.length, 7);
  assert.deepEqual(planned.anomalies, []);
  assert.equal((await runGameWindow(ctx)).reason, "no-active-games");
});

test("confirmed postseason date schedules exactly one task, refreshes only when due", async () => {
  const ctx = await runtime("2026-10-25", { "2026-10/3,4,5,7": payload(october), "2026-11/3,4,5,7": payload(november) });
  await runPlanDay(ctx);
  assert.equal(ctx.scheduler.schedules.size, 1);
  assert.equal(ctx.scheduler.schedules.get(ctx.dateKey), "2026-10-25T04:00:00.000Z");
  await ctx.source.fetchSchedule(ctx.dateKey);
  assert.equal(ctx.calls.filter(c => c === "2026-10/3,4,5,7").length, 1);
  ctx.advance(ctx.config.polling.scheduleRefreshMs);
  await ctx.source.fetchSchedule(ctx.dateKey);
  assert.equal(ctx.calls.filter(c => c === "2026-10/3,4,5,7").length, 2);
});

test("unconfirmed-only date creates no game task and is discovered again on a later day", async () => {
  const ctx = await runtime("2026-11-02", { "2026-11/3,4,5,7": payload(november) });
  await runPlanDay(ctx);
  assert.equal(ctx.scheduler.schedules.size, 0);
  assert.equal((await ctx.state.getJson("SCHEDULE#MONTH#2026-11")).games.length, 0);
  await ctx.source.fetchSchedule("2026-11-03");
  assert.equal(ctx.calls.filter(c => c === "2026-11/3,4,5,7").length, 2);
});

test("later existing planner run discovers newly confirmed teams on the same day", async () => {
  const responses = { "2026-11/3,4,5,7": payload(november) };
  const ctx = await runtime("2026-11-02", responses);
  await runPlanDay(ctx);
  assert.equal(ctx.scheduler.schedules.size, 0);
  const $ = load(november);
  $("td.play > span").each((_, e) => { if ($(e).text().replace(/\s/g, "") === "PO승리팀") $(e).text("KIA"); });
  responses["2026-11/3,4,5,7"] = payload($.html());
  ctx.advance(6 * 3600000);
  await runPlanDay(ctx);
  assert.equal(ctx.scheduler.schedules.size, 1);
  assert.equal((await ctx.state.getJson("SCHEDULE#MONTH#2026-11")).deferredGames.length, 0);
});

test("off-day publishes complete postseason coverage without inventing a canceled game", async () => {
  const ctx = await runtime("2026-10-23", { "2026-10/3,4,5,7": payload(october) });
  const old = parseKboScheduleMonthPage(october, "2026-10").games.find(g => g.date === "2026-10-25");
  await ctx.state.putJson("DISCOVERY#POSTSEASON#2026-10", { checkedDate: "2026-10-22", observedAt: 0,
    result: { games: [{ ...old, date: "2026-10-23" }], deferredGames: [] } });
  const today = await ctx.source.fetchSchedule(ctx.dateKey);
  assert.deepEqual(today.games, []);
  assert.ok(today.monthlySchedule.scheduleCoverage.some(c => c.gameType === "PLAYOFF"));
});

test("legacy deployment cache is refreshed and month-end planning covers next month's regular fixtures", async () => {
  const ctx = await runtime("2026-10-27", {"2026-10/3,4,5,7":payload(october)});
  await ctx.state.putJson("DISCOVERY#POSTSEASON#2026-10",{checkedDate:ctx.dateKey,observedAt:ctx.now(),
    result:{games:[],anomalies:[],offDays:[],deferredGames:[]}});
  await runPlanDay(ctx);
  assert.ok(ctx.calls.includes("2026-10/3,4,5,7"));
  assert.ok(ctx.calls.includes("2026-11/0,9,6"));
  const month = await ctx.state.getJson("SCHEDULE#MONTH#2026-11");
  assert.ok(month.scheduleCoverage.some(c=>c.gameType==='REGULAR'));
  assert.ok(month.scheduleCoverage.some(c=>c.gameType==='KOREAN_SERIES'));
});
