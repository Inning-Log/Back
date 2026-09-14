import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { stampPageObservation } from "../src/fargate/source.js";
import { loadFargateConfig } from "../src/fargate/config.js";
import {
  KboPageSchemaError,
  allGamesTerminal,
  mergeKboPages,
  parseKboScheduleMonthPage,
  parseKboSchedulePage,
  parseKboScoreboardPage,
} from "../src/fargate/kbo-pages.js";

async function fixtures() {
  const { config } = await loadFargateConfig({ env: {} });
  return Promise.all([
    readFile(config.fixture.scheduleFile, "utf8"),
    readFile(config.fixture.scoreboardFile, "utf8"),
  ]);
}

test("KBO schedule page parser handles row-spanned dates and cancellation notes", async () => {
  const [html] = await fixtures();
  const result = parseKboSchedulePage(html, "2026-08-26");
  assert.equal(result.games.length, 2);
  assert.equal(result.games[0].awayTeam.code, "NC");
  assert.equal(result.games[0].homeTeam.code, "LG");
  assert.equal(result.games[0].stadium, "잠실");
  assert.equal(result.games[0].scheduledAt, "2026-08-26T09:30:00.000Z");
  assert.equal(result.games[1].status, "CANCELLED");
  assert.equal(result.games[1].stadium, "문학");
});

test("KBO schedule month parser preserves every displayed date from one response", async () => {
  const [html] = await fixtures();
  const result = parseKboScheduleMonthPage(html, "2026-08");
  assert.equal(result.pageDate, "2026-08");
  assert.equal(result.games.length, 4);
  assert.deepEqual([...new Set(result.games.map((game) => game.date))], ["2026-08-25", "2026-08-26", "2026-08-27"]);
  assert.equal(result.games[0].status, "FINISHED");
  assert.deepEqual(result.games[0].score, { away: 4, home: 7 });
  assert.equal(result.games.find((game) => game.date === "2026-08-27").scheduledAt, "2026-08-27T09:30:00.000Z");
});

test("KBO scoreboard parser extracts the seven requested fields without play events", async () => {
  const [, html] = await fixtures();
  const result = parseKboScoreboardPage(html, "2026-08-26");
  const game = result.games[0];
  assert.equal(game.externalId.kbo, "20260826NCLG0");
  assert.equal(game.status, "LIVE");
  assert.deepEqual(game.score, { away: 0, home: 5 });
  assert.equal(game.inning, 6);
  assert.equal(game.half, "BOTTOM");
  assert.equal(game.startedAt, null);
  assert.equal(game.endedAt, null);
  assert.deepEqual(game.events, []);
});

test("schedule and scoreboard merge only on exact id or unambiguous team identity", async () => {
  const [scheduleHtml, scoreboardHtml] = await fixtures();
  const schedule = parseKboSchedulePage(scheduleHtml, "2026-08-26");
  const scoreboard = parseKboScoreboardPage(scoreboardHtml, "2026-08-26");
  const result = mergeKboPages(schedule.games, scoreboard.games);
  assert.equal(result.games.length, 2);
  assert.equal(result.games.find((game) => game.homeTeam.code === "LG").status, "LIVE");
  assert.equal(result.games.find((game) => game.homeTeam.code === "SSG").status, "CANCELLED");
  assert.equal(allGamesTerminal(result.games), false);

  const delayed = structuredClone(schedule.games[0]);
  delayed.status = "DELAYED";
  delayed.statusText = "우천지연";
  const pregame = structuredClone(scoreboard.games[0]);
  pregame.status = "SCHEDULED";
  pregame.statusText = "경기예정";
  pregame.score = { away: null, home: null };
  pregame.inning = null;
  pregame.half = null;
  assert.equal(mergeKboPages([delayed], [pregame]).games[0].status, "DELAYED");
});

test("page/date/schema drift fails closed instead of guessing", async () => {
  const [scheduleHtml, scoreboardHtml] = await fixtures();
  assert.throws(
    () => parseKboSchedulePage(scheduleHtml.replace("tblScheduleList", "renamed"), "2026-08-26"),
    KboPageSchemaError
  );
  assert.throws(
    () => parseKboSchedulePage(scheduleHtml, "2026-09-01"),
    KboPageSchemaError
  );
  assert.throws(
    () => parseKboScoreboardPage(scoreboardHtml.replace("5</span>", "4</span>"), "2026-08-26"),
    KboPageSchemaError
  );
  assert.throws(
    () => parseKboScoreboardPage(scoreboardHtml, "2026-08-27"),
    KboPageSchemaError
  );
});

test("game classification is explicit and missing selectors stay UNKNOWN", async () => {
  const [html] = await fixtures();
  assert.equal(parseKboScheduleMonthPage(html,"2026-08").games[0].gameType,"REGULAR");
  assert.equal(parseKboScheduleMonthPage(html.replace(/<select id="ddlSeries">.*?<\/select>/,""),"2026-08").games[0].gameType,"UNKNOWN");
  assert.equal(parseKboScheduleMonthPage(html.replace("정규시즌","시범경기"),"2026-08").games[0].gameType,"EXHIBITION");
  assert.equal(parseKboScheduleMonthPage(html,"2026-08").games[0].gameSequence,null);
});

test("merged cached scoreboard retains its original observation and schedule owns schedule fields", async () => {
  const [scheduleHtml,scoreboardHtml] = await fixtures();
  const schedule = stampPageObservation(parseKboSchedulePage(scheduleHtml,"2026-08-26"),"schedule","2026-08-26T10:15:00Z");
  const scoreboard = stampPageObservation(parseKboScoreboardPage(scoreboardHtml,"2026-08-26"),"scoreboard","2026-08-26T10:10:00Z");
  const merged = mergeKboPages(schedule.games,scoreboard.games).games.find(game => game.homeTeam.code === "LG");
  assert.equal(merged.meta.scheduleObservedAt,"2026-08-26T10:15:00.000Z");
  assert.equal(merged.meta.resultObservedAt,"2026-08-26T10:10:00.000Z");
  assert.equal(merged.meta.resultSource,"SCOREBOARD");
  scoreboard.games[0].status = "UNKNOWN";
  schedule.games[0].status = "FINISHED";
  schedule.games[0].score = {home:7,away:5};
  const final = mergeKboPages(schedule.games,scoreboard.games).games.find(game => game.homeTeam.code === "LG");
  assert.deepEqual(final.score,{home:7,away:5});
  assert.equal(final.meta.resultSource,"SCHEDULE");
});
