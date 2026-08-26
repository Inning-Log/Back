import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { loadFargateConfig } from "../src/fargate/config.js";
import {
  KboPageSchemaError,
  allGamesTerminal,
  mergeKboPages,
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
