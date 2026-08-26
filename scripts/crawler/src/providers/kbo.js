import { load } from "cheerio";
import { normalizeGameSnapshot } from "../domain.js";
import {
  ProviderSchemaError,
  assertGameIdentity,
  arrayAtKnownPath,
  buildUrl,
  dateCompact,
  firstPresent,
  parseJsonEnvelope,
  scheduleFieldAnomalies,
} from "./utils.js";

const SCHEDULE_ARRAY_PATHS = [
  "games",
  "gameList",
  "result.games",
  "result.gameList",
  "data.games",
  "data.gameList",
  "data",
];

function text(value) {
  return value == null ? "" : String(value).replace(/\s+/g, " ").trim();
}

function teamName(value) {
  if (typeof value === "string" || typeof value === "number") return text(value);
  return text(
    firstPresent(value, ["name", "teamName", "nameKr", "shortName"])
  );
}

function normalizeDateKey(value, fallback) {
  const raw = text(value);
  if (/^\d{8}$/.test(raw)) {
    return `${raw.slice(0, 4)}-${raw.slice(4, 6)}-${raw.slice(6, 8)}`;
  }
  if (/^\d{4}-\d{2}-\d{2}/.test(raw)) return raw.slice(0, 10);
  return fallback;
}

function scheduledValue(value, dateKey) {
  const raw = text(value);
  if (!raw) return null;
  if (/^\d{1,2}:\d{2}(?::\d{2})?$/.test(raw)) {
    const normalized = raw.length === 5 ? `${raw}:00` : raw;
    return `${dateKey}T${normalized}+09:00`;
  }
  return raw;
}

function mapKboRow(row, targetDate, index) {
  const date = normalizeDateKey(
    firstPresent(row, ["gameDate", "schedDate", "matchDate", "date", "GAME_DATE"]),
    targetDate
  );
  const homeRaw = firstPresent(row, [
    "homeName",
    "homeTeamName",
    "homeTeam",
    "homeNameKr",
    "home",
    "HOME_NM",
  ]);
  const awayRaw = firstPresent(row, [
    "awayName",
    "awayTeamName",
    "awayTeam",
    "awayNameKr",
    "away",
    "AWAY_NM",
  ]);
  const rawScheduled = firstPresent(row, [
    "gameDateTime",
    "startAt",
    "gameTime",
    "startTime",
    "GAME_TIME",
  ]);

  return assertGameIdentity(normalizeGameSnapshot({
    date,
    source: { kbo: true, naver: false },
    externalId: {
      kbo: text(firstPresent(row, ["gameId", "GameID", "id", "gameKey", "seq"])),
      naver: null,
    },
    scheduledAt: scheduledValue(rawScheduled, date),
    startedAt: scheduledValue(
      firstPresent(row, ["startedAt", "gameStart", "actualStartTime"]),
      date
    ),
    endedAt: scheduledValue(
      firstPresent(row, ["endedAt", "gameEnd", "actualEndTime"]),
      date
    ),
    homeTeam: teamName(homeRaw),
    awayTeam: teamName(awayRaw),
    stadium: text(
      firstPresent(row, ["stadiumName", "stadium", "place", "ground", "STADIUM_NM"])
    ),
    status: firstPresent(row, [
      "gameState",
      "matchState",
      "state",
      "status",
      "GameState",
      "GAME_STATE",
    ]),
    statusText: text(firstPresent(row, ["statusText", "gameState", "status"])),
    score: {
      home: firstPresent(row, ["homeScore", "scoreHome", "HOME_SCORE"]),
      away: firstPresent(row, ["awayScore", "scoreAway", "AWAY_SCORE"]),
    },
    inning: firstPresent(row, ["inning", "currentInning", "CurrentInning"]),
    half: firstPresent(row, ["half", "topBottom", "inningHalf", "isTop"]),
    gameNumber: firstPresent(row, [
      "doubleheaderNumber",
      "gameNumber",
      "dhNo",
    ]),
    events: [],
    meta: { provenance: [{ provider: "kbo", row: index }] },
  }), "kbo", index);
}

export function parseKboScheduleJson(rawPayload, targetDate, options = {}) {
  const payload = parseJsonEnvelope(rawPayload, "kbo");
  const rows = arrayAtKnownPath(
    payload,
    options.arrayPaths ?? SCHEDULE_ARRAY_PATHS,
    "kbo",
    "schedule"
  );
  const games = [];
  const anomalies = [];
  const maximum = options.maxGames ?? 10;

  for (const [index, row] of rows.slice(0, maximum).entries()) {
    try {
      const game = mapKboRow(row, targetDate, index);
      games.push(game);
      anomalies.push(...scheduleFieldAnomalies(game, "kbo", index));
    } catch (error) {
      anomalies.push({
        provider: "kbo",
        type: "INVALID_SCHEDULE_ROW",
        row: index,
        message: error.message,
      });
    }
  }
  if (rows.length > 0 && games.length === 0) {
    throw new ProviderSchemaError("kbo", "모든 schedule 행이 canonical 검증에 실패했습니다.", {
      anomalies,
    });
  }
  return { games, anomalies };
}

export function parseKboScheduleHtml(html, targetDate, options = {}) {
  const selectors = options.selectors;
  if (!selectors?.row) {
    throw new ProviderSchemaError("kbo", "HTML selector 설정이 없습니다.");
  }
  const $ = load(html);
  const rows = $(selectors.row).toArray();
  if (rows.length === 0) {
    if (/경기가\s*(없|없습니다)|no\s+games/i.test($.root().text())) {
      return { games: [], anomalies: [] };
    }
    throw new ProviderSchemaError("kbo", "HTML selector가 어떤 경기 행도 찾지 못했습니다.");
  }

  const games = [];
  const anomalies = [];
  const maximum = options.maxGames ?? 10;
  let gameIdRegex = null;
  if (options.gameIdFromHrefRegex) {
    try {
      gameIdRegex = new RegExp(options.gameIdFromHrefRegex);
    } catch (error) {
      throw new ProviderSchemaError(
        "kbo",
        "gameIdFromHrefRegex 설정이 유효한 정규식이 아닙니다.",
        { cause: error.message }
      );
    }
  }
  for (const [index, element] of rows.slice(0, maximum).entries()) {
    const row = $(element);
    const get = (selector) => text(row.find(selector).first().text());
    let gameId = null;
    if (gameIdRegex) {
      for (const link of row.find("a[href]").toArray()) {
        const href = $(link).attr("href") ?? "";
        const match = gameIdRegex.exec(href);
        gameIdRegex.lastIndex = 0;
        gameId = match?.[1] ?? match?.[0] ?? null;
        if (gameId) break;
      }
    }
    try {
      const game = assertGameIdentity(normalizeGameSnapshot({
          date: targetDate,
          source: { kbo: true, naver: false },
          externalId: { kbo: gameId, naver: null },
          scheduledAt: scheduledValue(get(selectors.dateTime), targetDate),
          homeTeam: get(selectors.homeTeam),
          awayTeam: get(selectors.awayTeam),
          stadium: get(selectors.stadium),
          status: get(selectors.status),
          statusText: get(selectors.status),
          score: {
            home: get(selectors.homeScore),
            away: get(selectors.awayScore),
          },
          inning: get(selectors.inning),
          half: get(selectors.inningHalf),
          events: [],
          meta: { provenance: [{ provider: "kbo", row: index, format: "html" }] },
        }), "kbo", index);
      games.push(game);
      anomalies.push(...scheduleFieldAnomalies(game, "kbo", index));
    } catch (error) {
      anomalies.push({
        provider: "kbo",
        type: "INVALID_HTML_ROW",
        row: index,
        message: error.message,
      });
    }
  }
  if (games.length === 0) {
    throw new ProviderSchemaError("kbo", "모든 HTML 경기 행의 검증이 실패했습니다.", {
      anomalies,
    });
  }
  return { games, anomalies };
}

export async function fetchKboSchedule({ dateKey, config, http }) {
  const provider = config.providers.kbo;
  const template = provider.schedule?.urlTemplate;
  const url = buildUrl(template, {
    date: dateKey,
    dateYYYYMMDD: dateCompact(dateKey),
    dateYYYY_MM_DD: dateKey,
  });
  const raw = await http.requestText("kbo", "schedule", url);
  const trimmed = raw.trimStart();
  const options = {
    maxGames: config.run.maxGamesPerRun,
    selectors: provider.scheduleSelectors,
    gameIdFromHrefRegex: provider.gameIdFromHrefRegex,
    arrayPaths: provider.schedule?.arrayPaths,
  };
  return trimmed.startsWith("<")
    ? parseKboScheduleHtml(raw, dateKey, options)
    : parseKboScheduleJson(raw, dateKey, options);
}
