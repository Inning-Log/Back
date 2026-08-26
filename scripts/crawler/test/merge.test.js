import test from "node:test";
import assert from "node:assert/strict";

import {
  matchHybridGames,
  mergeHybridGames,
} from "../src/merge.js";

function game(overrides = {}) {
  return {
    date: "2026-08-25",
    homeTeam: "LG",
    awayTeam: "두산",
    status: "SCHEDULED",
    ...overrides,
  };
}

test("hybrid merge joins a unique date/team/time game and preserves provenance", () => {
  const result = mergeHybridGames(
    [game({ scheduledAt: "2026-08-25T18:30:00+09:00", stadium: "잠실", status: "LIVE", homeScore: 1 })],
    [game({ scheduledAt: "2026-08-25T18:31:00+09:00", status: "UNKNOWN", homeScore: 0, awayScore: 2 })]
  );

  assert.equal(result.matches.length, 1);
  assert.equal(result.anomalies.length, 0);
  assert.equal(result.games.length, 1);
  assert.deepEqual(result.games[0].source, { kbo: true, naver: true });
  assert.equal(result.games[0].status, "LIVE");
  assert.deepEqual(result.games[0].score, { home: 0, away: 2 });
  assert.equal(result.games[0].stadium, "잠실");
});

test("doubleheaders match one-to-one by scheduled time even when provider order differs", () => {
  const left = [
    game({ scheduledAt: "2026-08-25T14:00:00+09:00" }),
    game({ scheduledAt: "2026-08-25T18:00:00+09:00" }),
  ];
  const right = [
    game({ scheduledAt: "2026-08-25T18:02:00+09:00" }),
    game({ scheduledAt: "2026-08-25T14:01:00+09:00" }),
  ];
  const result = matchHybridGames(left, right);

  assert.deepEqual(result.matches.map(({ leftIndex, rightIndex }) => [leftIndex, rightIndex]), [[0, 1], [1, 0]]);
  assert.equal(result.anomalies.length, 0);
});

test("explicit doubleheader game numbers beat shifted or missing start times", () => {
  const left = [
    game({ gameNumber: 1, scheduledAt: "2026-08-25T14:00:00+09:00" }),
    game({ gameNumber: 2, scheduledAt: "2026-08-25T18:00:00+09:00" }),
  ];
  const right = [game({ gameNumber: 2, scheduledAt: null })];
  const result = matchHybridGames(left, right);

  assert.deepEqual(result.matches.map(({ leftIndex, rightIndex, by }) => [leftIndex, rightIndex, by]), [[1, 0, "GAME_NUMBER"]]);
  assert.deepEqual(result.unmatched, { left: [0], right: [] });
});

test("indistinguishable doubleheaders return an anomaly instead of a guessed merge", () => {
  const left = [game(), game()];
  const right = [game(), game()];
  const result = mergeHybridGames(left, right);

  assert.equal(result.matches.length, 0);
  assert.equal(result.games.length, 4);
  assert.equal(result.anomalies.length, 1);
  assert.equal(result.anomalies[0].type, "AMBIGUOUS_MATCH");
  assert.deepEqual(result.anomalies[0].leftIndexes, [0, 1]);
  assert.deepEqual(result.anomalies[0].rightIndexes, [0, 1]);
});

test("when only one doubleheader appears on the right, nearest time remains one-to-one", () => {
  const left = [
    game({ scheduledAt: "2026-08-25T14:00:00+09:00" }),
    game({ scheduledAt: "2026-08-25T18:00:00+09:00" }),
  ];
  const right = [game({ scheduledAt: "2026-08-25T18:05:00+09:00" })];
  const result = matchHybridGames(left, right);

  assert.deepEqual(result.matches.map(({ leftIndex, rightIndex }) => [leftIndex, rightIndex]), [[1, 0]]);
  assert.deepEqual(result.unmatched, { left: [0], right: [] });
});

test("large time conflicts are left unmatched and reported", () => {
  const result = matchHybridGames(
    [game({ scheduledAt: "2026-08-25T10:00:00+09:00" })],
    [game({ scheduledAt: "2026-08-25T20:00:00+09:00" })],
    { timeToleranceMinutes: 180 }
  );
  assert.equal(result.matches.length, 0);
  assert.equal(result.anomalies[0].type, "TIME_MISMATCH");
});

test("default matching never joins same-team games more than two hours apart", () => {
  const result = matchHybridGames(
    [game({ scheduledAt: "2026-08-25T14:00:00+09:00" })],
    [game({ scheduledAt: "2026-08-25T17:00:01+09:00" })]
  );
  assert.equal(result.matches.length, 0);
  assert.equal(result.anomalies[0].type, "TIME_MISMATCH");
});

test("conflicting explicit doubleheader numbers are not merged", () => {
  const result = matchHybridGames([game({ gameNumber: 1 })], [game({ gameNumber: 2 })]);
  assert.equal(result.matches.length, 0);
  assert.equal(result.anomalies[0].type, "GAME_NUMBER_MISMATCH");
});
