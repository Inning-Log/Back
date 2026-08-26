import test from "node:test";
import assert from "node:assert/strict";

import {
  parseKboScheduleHtml,
  parseKboScheduleJson,
} from "../src/providers/kbo.js";
import {
  fetchNaverDetail,
  parseNaverDetail,
  parseNaverSchedule,
} from "../src/providers/naver.js";
import { ProviderSchemaError } from "../src/providers/utils.js";

const DATE = "2026-08-26";
const CANONICAL_GAME_KEYS = [
  "date",
  "gameNumber",
  "source",
  "externalId",
  "scheduledAt",
  "startedAt",
  "endedAt",
  "homeTeam",
  "awayTeam",
  "stadium",
  "status",
  "statusText",
  "score",
  "inning",
  "half",
  "events",
  "meta",
];

function assertCanonicalGame(game) {
  assert.deepEqual(Object.keys(game), CANONICAL_GAME_KEYS);
  assert.deepEqual(Object.keys(game.source), ["kbo", "naver"]);
  assert.deepEqual(Object.keys(game.externalId), ["kbo", "naver"]);
  assert.deepEqual(Object.keys(game.score), ["home", "away"]);
  assert.deepEqual(Object.keys(game.homeTeam), ["code", "name"]);
  assert.deepEqual(Object.keys(game.awayTeam), ["code", "name"]);
  assert.ok(Array.isArray(game.events));
  assert.equal(typeof game.meta, "object");
}

function assertSchemaMismatch(operation, provider) {
  assert.throws(operation, (error) => {
    assert.ok(error instanceof ProviderSchemaError);
    assert.equal(error.code, "PROVIDER_SCHEMA_MISMATCH");
    assert.equal(error.provider, provider);
    return true;
  });
}

test("KBO JSON parser converts an ASMX envelope to canonical games", () => {
  const rawPayload = JSON.stringify({
    d: JSON.stringify({
      gameList: [
        {
          GameID: "20260826LGOO0",
          gameDate: "20260826",
          gameTime: "18:30",
          homeTeamName: "LG 트윈스",
          awayTeamName: "두산 베어스",
          stadiumName: "잠실",
          gameState: "경기중",
          homeScore: "3",
          awayScore: "2",
          currentInning: "7회초",
          doubleheaderNumber: "1",
        },
      ],
    }),
  });

  const { games, anomalies } = parseKboScheduleJson(rawPayload, DATE);

  assert.equal(anomalies.length, 0);
  assert.equal(games.length, 1);
  assertCanonicalGame(games[0]);
  assert.deepEqual(games[0], {
    date: DATE,
    gameNumber: 1,
    source: { kbo: true, naver: false },
    externalId: { kbo: "20260826LGOO0", naver: null },
    scheduledAt: "2026-08-26T09:30:00.000Z",
    startedAt: null,
    endedAt: null,
    homeTeam: { code: "LG", name: "LG" },
    awayTeam: { code: "DOO", name: "두산" },
    stadium: "잠실",
    status: "LIVE",
    statusText: "경기중",
    score: { home: 3, away: 2 },
    inning: 7,
    half: "TOP",
    events: [],
    meta: { provenance: [{ provider: "kbo", row: 0 }] },
  });

  const objectEnvelope = parseKboScheduleJson(
    {
      d: {
        games: [
          { date: "20260826", gameTime: "18:30", home: "LG", away: "두산" },
        ],
      },
    },
    DATE
  );
  assert.equal(objectEnvelope.games.length, 1);
});

test("KBO HTML parser uses configured selectors and returns canonical games", () => {
  const html = `
    <table>
      <tbody>
        <tr class="game-row">
          <td class="time">14:00</td>
          <td class="away">한화 이글스</td>
          <td class="away-score">3</td>
          <td class="home">KIA 타이거즈</td>
          <td class="home-score">4</td>
          <td class="stadium">광주</td>
          <td class="state">경기 종료</td>
          <td class="inning">9회</td>
          <td class="half">말</td>
          <td><a href="/game/98765">상세</a></td>
        </tr>
      </tbody>
    </table>`;
  const selectors = {
    row: ".game-row",
    dateTime: ".time",
    homeTeam: ".home",
    awayTeam: ".away",
    stadium: ".stadium",
    status: ".state",
    homeScore: ".home-score",
    awayScore: ".away-score",
    inning: ".inning",
    inningHalf: ".half",
  };

  const { games, anomalies } = parseKboScheduleHtml(html, DATE, {
    selectors,
    gameIdFromHrefRegex: "game/(\\d+)",
  });

  assert.equal(anomalies.length, 0);
  assert.equal(games.length, 1);
  assertCanonicalGame(games[0]);
  assert.equal(games[0].scheduledAt, "2026-08-26T05:00:00.000Z");
  assert.deepEqual(games[0].awayTeam, { code: "HH", name: "한화" });
  assert.deepEqual(games[0].homeTeam, { code: "KIA", name: "KIA" });
  assert.deepEqual(games[0].score, { home: 4, away: 3 });
  assert.equal(games[0].status, "FINISHED");
  assert.equal(games[0].inning, 9);
  assert.equal(games[0].half, "BOTTOM");
  assert.equal(games[0].externalId.kbo, "98765");
  assert.deepEqual(games[0].meta.provenance, [
    { provider: "kbo", row: 0, format: "html" },
  ]);
});

test("Naver schedule parser converts nested provider fields to canonical games", () => {
  const payload = {
    data: {
      games: [
        {
          gameId: "NAVER-1",
          gameDate: "20260826",
          startTime: "18:30",
          homeTeam: { name: "삼성 라이온즈", score: "0" },
          awayTeam: { name: "롯데 자이언츠", score: "1" },
          stadiumName: "대구",
          gameState: "진행중",
          currentInning: "6",
          isTop: false,
          gameNumber: 2,
        },
      ],
    },
  };

  const { games, anomalies } = parseNaverSchedule(payload, DATE);

  assert.equal(anomalies.length, 0);
  assert.equal(games.length, 1);
  assertCanonicalGame(games[0]);
  assert.equal(games[0].date, DATE);
  assert.equal(games[0].gameNumber, 2);
  assert.deepEqual(games[0].source, { kbo: false, naver: true });
  assert.deepEqual(games[0].externalId, { kbo: null, naver: "NAVER-1" });
  assert.equal(games[0].scheduledAt, "2026-08-26T09:30:00.000Z");
  assert.deepEqual(games[0].homeTeam, { code: "SS", name: "삼성" });
  assert.deepEqual(games[0].awayTeam, { code: "LT", name: "롯데" });
  assert.deepEqual(games[0].score, { home: 0, away: 1 });
  assert.equal(games[0].status, "LIVE");
  assert.equal(games[0].inning, 6);
  assert.equal(games[0].half, "BOTTOM");
});

test("Naver detail parser returns a canonical patch and removes descriptions by default", () => {
  const payload = {
    result: {
      gameState: "경기중",
      statusText: "7회말 진행중",
      startTime: "18:32",
      homeScore: "3",
      awayScore: "2",
      currentInning: "7",
      isTop: false,
      plays: [
        {
          id: "play-1",
          inning: "7회말",
          batterName: "홍길동",
          pitcherName: "김투수",
          description: "3루수 앞 내야안타",
          rbi: "0",
        },
        {
          id: "play-2",
          inning: "7회말",
          description: "우중간 2루타",
          rbi: "1",
        },
      ],
    },
  };
  const game = { date: DATE };

  const { patch, recordComplete } = parseNaverDetail(payload, game);

  assert.equal(recordComplete, false);
  assert.equal(patch.status, "LIVE");
  assert.equal(patch.statusText, "7회말 진행중");
  assert.equal(patch.startedAt, "2026-08-26T09:32:00.000Z");
  assert.deepEqual(patch.score, { home: 3, away: 2 });
  assert.equal(patch.inning, 7);
  assert.equal(patch.half, "BOTTOM");
  assert.deepEqual(
    patch.events.map(({ eventType, description, runs }) => ({ eventType, description, runs })),
    [
      { eventType: "SINGLE", description: null, runs: 0 },
      { eventType: "DOUBLE", description: null, runs: 1 },
    ]
  );
  assert.deepEqual(patch.meta.provenance, [{ provider: "naver", detail: "relay" }]);
});

test("Naver detail descriptions require an explicit opt-in and record completion is strict", () => {
  const payload = {
    result: {
      status: "경기 종료",
      homeScore: 5,
      awayScore: 4,
      plays: [{ description: "좌중간 3루타" }],
    },
  };

  const { patch, recordComplete } = parseNaverDetail(payload, { date: DATE }, {
    kind: "record",
    includeDescriptions: true,
  });

  assert.equal(recordComplete, true);
  assert.equal(patch.events[0].eventType, "TRIPLE");
  assert.equal(patch.events[0].description, "좌중간 3루타");
});

test("maxEvents=0 removes all detail events instead of treating -0 as unbounded", () => {
  const payload = {
    result: {
      status: "경기중",
      homeScore: 1,
      awayScore: 0,
      plays: [{ description: "안타" }, { description: "2루타" }],
    },
  };
  const { patch } = parseNaverDetail(payload, { date: DATE }, {
    maxEvents: 0,
    includeDescriptions: true,
  });
  assert.equal(Object.hasOwn(patch, "events"), false);
});

test("Naver fetch adapter preserves configured nested result.plays paths", async () => {
  const game = {
    date: DATE,
    status: "LIVE",
    externalId: { naver: "NAVER-NESTED" },
  };
  const config = {
    data: { maxEventsPerGame: 5, includePlayDescriptions: false },
    providers: {
      naver: {
        enabled: true,
        authorization: { scopes: ["relay"] },
        relay: { urlTemplate: "https://example.test/{gameId}/relay" },
        record: { urlTemplate: "https://example.test/{gameId}/record" },
        keys: {
          relayPlayCandidates: ["plays", "result.plays"],
          inningArrayCandidates: ["innings", "result.innings"],
        },
      },
    },
  };
  const http = {
    requestJson: async () => ({
      result: {
        status: "경기중",
        homeScore: 1,
        awayScore: 0,
        plays: [{ id: "nested-1", description: "좌전 안타" }],
      },
    }),
  };

  const { patch } = await fetchNaverDetail({ game, config, http });
  assert.equal(patch.events.length, 1);
  assert.equal(patch.events[0].id, "nested-1");
  assert.equal(patch.events[0].eventType, "SINGLE");
  assert.equal(patch.events[0].description, null);
});

test("schedule and HTML schema drift fails closed instead of returning guessed data", () => {
  assertSchemaMismatch(
    () => parseKboScheduleJson({ renamedGames: [{ home: "LG", away: "두산" }] }, DATE),
    "kbo"
  );
  assertSchemaMismatch(
    () => parseNaverSchedule({ renamedGames: [{ home: "LG", away: "두산" }] }, DATE),
    "naver"
  );
  assertSchemaMismatch(
    () => parseKboScheduleHtml("<table><tr class='renamed-row'></tr></table>", DATE, {
      selectors: { row: ".expected-row" },
    }),
    "kbo"
  );
});

test("Naver detail schema drift fails closed", () => {
  assertSchemaMismatch(
    () => parseNaverDetail({ renamedDetail: { scoreOfHome: 1 } }, { date: DATE }),
    "naver"
  );
});
