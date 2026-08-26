import test from "node:test";
import assert from "node:assert/strict";

import {
  applyGamePatch,
  normalizeDate,
  normalizeDateTime,
  normalizeEvent,
  normalizeGameSnapshot,
  normalizeHalf,
  normalizeInning,
  normalizeScore,
  normalizeStatus,
  normalizeTeam,
} from "../src/domain.js";

test("scores accept only non-negative integers", () => {
  assert.equal(normalizeScore(0), 0);
  assert.equal(normalizeScore("12"), 12);
  for (const invalid of [-1, 1.5, "-1", "3점", "3-2", "", null]) {
    assert.equal(normalizeScore(invalid), null);
  }
});

test("dates and KST date-times are strict and rollover-safe", () => {
  assert.equal(normalizeDate("20260825"), "2026-08-25");
  assert.equal(normalizeDate("2026.8.5 18:30"), "2026-08-05");
  assert.equal(normalizeDate("2026-02-29"), null);
  assert.equal(normalizeDate("2024-02-29"), "2024-02-29");
  assert.equal(normalizeDateTime("18:30", "2026-08-25"), "2026-08-25T09:30:00.000Z");
  assert.equal(normalizeDateTime(202608251830), "2026-08-25T09:30:00.000Z");
  assert.equal(normalizeDateTime("2026-08-25T18:30:00+09:00"), "2026-08-25T09:30:00.000Z");
  assert.equal(normalizeDateTime("2026-02-29 18:30"), null);
  assert.equal(normalizeDateTime("24:00", "2026-08-25"), null);
});

test("inning parser handles number, Korean compact text, and boolean top", () => {
  assert.deepEqual(normalizeInning(7, true), { number: 7, half: "TOP" });
  assert.deepEqual(normalizeInning("7회초"), { number: 7, half: "TOP" });
  assert.deepEqual(normalizeInning("7초"), { number: 7, half: "TOP" });
  assert.deepEqual(normalizeInning("top 7"), { number: 7, half: "TOP" });
  assert.deepEqual(normalizeInning({ inningNo: "7", isTop: false }), { number: 7, half: "BOTTOM" });
  assert.equal(normalizeHalf(false), "BOTTOM");
  assert.deepEqual(normalizeInning("7회중"), { number: null, half: null });
});

test("statuses and KBO team aliases normalize to canonical values", () => {
  assert.equal(normalizeStatus("경기 진행중"), "LIVE");
  assert.equal(normalizeStatus("우천 취소"), "CANCELLED");
  assert.equal(normalizeStatus("경기 지연"), "DELAYED");
  assert.equal(normalizeStatus("unrecognized"), "UNKNOWN");
  assert.deepEqual(normalizeTeam("두산 베어스"), { code: "DOO", name: "두산" });
  assert.deepEqual(normalizeTeam({ code: "WO", name: "키움 히어로즈" }), { code: "WOE", name: "키움" });
});

test("natural event classification does not confuse fielders with extra-base hits", () => {
  assert.equal(normalizeEvent({ description: "2루수 땅볼 아웃" }).eventType, "OUT");
  assert.equal(normalizeEvent({ description: "3루수 앞 내야안타" }).eventType, "SINGLE");
  assert.equal(normalizeEvent({ description: "우중간 2루타" }).eventType, "DOUBLE");
  assert.equal(normalizeEvent({ description: "좌중간 3루타" }).eventType, "TRIPLE");
});

test("provider records produce one complete GameSnapshot shape", () => {
  const game = normalizeGameSnapshot({
    source: "naver",
    gameId: "N-1",
    gameDate: "20260825",
    gameTime: "18:30",
    homeName: "LG 트윈스",
    awayName: "두산 베어스",
    homeScore: "0",
    awayScore: "2",
    currentInning: "7회초",
    state: "경기중",
  });

  assert.deepEqual(Object.keys(game), [
    "date", "gameNumber", "source", "externalId", "scheduledAt", "startedAt", "endedAt",
    "homeTeam", "awayTeam", "stadium", "status", "statusText", "score", "inning", "half",
    "events", "meta",
  ]);
  assert.equal(game.date, "2026-08-25");
  assert.equal(game.scheduledAt, "2026-08-25T09:30:00.000Z");
  assert.deepEqual(game.source, { kbo: false, naver: true });
  assert.deepEqual(game.externalId, { kbo: null, naver: "N-1" });
  assert.deepEqual(game.score, { home: 0, away: 2 });
  assert.equal(game.inning, 7);
  assert.equal(game.half, "TOP");
});

test("conservative patches preserve useful values but allow zero and false semantics", () => {
  const base = normalizeGameSnapshot({
    source: "kbo",
    date: "2026-08-25",
    homeTeam: { name: "LG", code: "LG" },
    awayTeam: { name: "두산", code: "DOO" },
    stadium: "잠실",
    status: "LIVE",
    score: { home: 3, away: 2 },
    inning: 7,
    half: "TOP",
    events: [{ id: "e1", description: "안타" }],
    meta: { providerState: "healthy" },
  });

  const patched = applyGamePatch(base, {
    source: "naver",
    stadium: null,
    status: "UNKNOWN",
    score: { home: 0, away: null },
    inning: null,
    isTop: false,
    events: [],
    meta: { providerState: "UNKNOWN", observed: false },
  });

  assert.deepEqual(patched.source, { kbo: true, naver: true });
  assert.equal(patched.stadium, "잠실");
  assert.equal(patched.status, "LIVE");
  assert.deepEqual(patched.score, { home: 0, away: 2 });
  assert.equal(patched.inning, 7);
  assert.equal(patched.half, "BOTTOM");
  assert.equal(patched.events.length, 1);
  assert.deepEqual(patched.meta, { providerState: "healthy", observed: false });
});
