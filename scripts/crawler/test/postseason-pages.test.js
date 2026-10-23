import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { load } from "cheerio";
import { parseKboScheduleMonthPage, parseKboSchedulePage, parseKboScoreboardPage } from "../src/fargate/kbo-pages.js";
const october = await readFile(new URL("../fixtures/kbo/postseason-october-2026.html", import.meta.url), "utf8");
const november = await readFile(new URL("../fixtures/kbo/postseason-november-2026.html", import.meta.url), "utf8");
test("actual October postseason: 9 games classified and 9 off-days are not failures", () => {
  const result = parseKboScheduleMonthPage(october, "2026-10");
  assert.equal(result.games.length, 9);
  assert.equal(result.offDays.length, 9);
  assert.deepEqual(result.anomalies, []);
  assert.deepEqual(result.games.map(g => g.gameType), ["WILDCARD", ...Array(3).fill("SEMI_PLAYOFF"), ...Array(5).fill("PLAYOFF")]);
  assert.equal(parseKboSchedulePage(october, "2026-10-23").games.length, 0);
});

test("actual November unconfirmed bracket is retained outside publishable games", () => {
  const result = parseKboScheduleMonthPage(november, "2026-11");
  assert.equal(result.games.length, 0);
  assert.equal(result.deferredGames.length, 7);
  assert.deepEqual(result.anomalies, []);
  assert.ok(result.deferredGames.every(g => g.gameType === "KOREAN_SERIES" && g.reason === "UNCONFIRMED_TEAMS"));
  // Source markup can use a span line break; replace its visible placeholder through Cheerio.
  const $ = load(november);
  $("td.play > span").each((_, e) => { if ($(e).text().replace(/\s/g, "") === "PO승리팀") $(e).text("KIA"); });
  $("tbody tr").each((_, e) => { if ($(e).find("td.time").text()) $(e).children("td").last().text("KS 1차전"); });
  const resolved = parseKboScheduleMonthPage($.html(), "2026-11");
  assert.equal(resolved.games.length, 7);
  assert.ok(resolved.games.every(g => g.gameType === "KOREAN_SERIES"));
  assert.equal(resolved.deferredGames.length, 0);
});

test("unknown clubs and malformed game rows still fail instead of being hidden as off-days", () => {
  assert.throws(() => parseKboScheduleMonthPage(november.replaceAll("KT", "BOGUS"), "2026-11"));
  assert.throws(() => parseKboSchedulePage(october.replace("이동일", "알 수 없는 오류"), "2026-10-15"));
});

test("actual postseason scoreboard retains KIA 13:1 LG result and identity", async () => {
  const html = await readFile(new URL("../fixtures/kbo/postseason-scoreboard-20261020.html", import.meta.url), "utf8");
  const result = parseKboScoreboardPage(html, "2026-10-20");
  assert.equal(result.games[0].externalId.kbo, "20261020HTLG0");
  assert.deepEqual(result.games[0].score, { away: 13, home: 1 });
  assert.equal(result.games[0].status, "FINISHED");
});

