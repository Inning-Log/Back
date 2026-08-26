import {
  normalizeEvent,
  normalizeGamePatch,
  normalizeGameSnapshot,
} from "../domain.js";
import { assertProviderCapability } from "../policy.js";
import {
  ProviderSchemaError,
  assertGameIdentity,
  arrayAtKnownPath,
  buildUrl,
  dateCompact,
  firstPresent,
  getPath,
  parseJsonEnvelope,
  scheduleFieldAnomalies,
} from "./utils.js";

const SCHEDULE_ARRAY_PATHS = [
  "games",
  "result.games",
  "data.games",
  "schedule.games",
  "result",
];
const EVENT_ARRAY_PATHS = [
  "plays",
  "result.plays",
  "data.plays",
  "relay.plays",
  "playByPlay",
  "result.playByPlay",
  "events",
  "result.events",
];
const INNING_ARRAY_PATHS = [
  "innings",
  "result.innings",
  "data.innings",
  "scoreboard.innings",
];
const DETAIL_FIELD_PATHS = [
  "status",
  "gameState",
  "state",
  "statusType",
  "statusText",
  "message",
  "description",
  "startTime",
  "startedAt",
  "actualStartTime",
  "endTime",
  "endedAt",
  "actualEndTime",
  "inning",
  "currentInning",
  "round",
  "half",
  "inningHalf",
  "isTop",
  "topBottom",
  "homeScore",
  "awayScore",
  "homeTeam.score",
  "awayTeam.score",
  "home.score",
  "away.score",
  "score.home",
  "score.away",
];

function text(value) {
  return value == null ? "" : String(value).replace(/\s+/g, " ").trim();
}

function teamName(value) {
  if (typeof value === "string" || typeof value === "number") return text(value);
  return text(firstPresent(value, ["name", "teamName", "nameKr", "shortName"]));
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
    return `${dateKey}T${raw.length === 5 ? `${raw}:00` : raw}+09:00`;
  }
  return raw;
}

function mapScheduleRow(row, targetDate, index) {
  const date = normalizeDateKey(
    firstPresent(row, ["date", "gameDate", "matchDate"]),
    targetDate
  );
  const homeRaw = firstPresent(row, ["homeName", "homeTeam", "home", "homeTeamName"]);
  const awayRaw = firstPresent(row, ["awayName", "awayTeam", "away", "awayTeamName"]);
  return assertGameIdentity(normalizeGameSnapshot({
    date,
    source: { kbo: false, naver: true },
    externalId: {
      kbo: null,
      naver: text(firstPresent(row, ["gameId", "id", "game_id"])),
    },
    scheduledAt: scheduledValue(
      firstPresent(row, [
        "gameDateTime",
        "startAt",
        "startTime",
        "gameTime",
      ]),
      date
    ),
    startedAt: scheduledValue(firstPresent(row, ["startedAt", "actualStartTime"]), date),
    endedAt: scheduledValue(firstPresent(row, ["endedAt", "endTime"]), date),
    status: firstPresent(row, [
      "gameState",
      "state",
      "status",
      "statusType",
      "matchStatus",
    ]),
    statusText: text(firstPresent(row, ["statusText", "status", "matchStatus"])),
    inning: firstPresent(row, [
      "inning",
      "currentInning",
      "inningText",
      "scoreboard.inning",
    ]),
    half: firstPresent(row, ["half", "inningHalf", "topBottom", "isTop"]),
    homeTeam: teamName(homeRaw),
    awayTeam: teamName(awayRaw),
    stadium: text(firstPresent(row, ["stadium", "stadiumName"])),
    score: {
      home: firstPresent(row, ["homeScore", "homeTeam.score", "home.score"]),
      away: firstPresent(row, ["awayScore", "awayTeam.score", "away.score"]),
    },
    gameNumber: firstPresent(row, [
      "doubleheaderNumber",
      "gameNumber",
      "dhNo",
    ]),
    events: [],
    meta: { provenance: [{ provider: "naver", row: index }] },
  }), "naver", index);
}

export function parseNaverSchedule(rawPayload, targetDate, options = {}) {
  const payload = parseJsonEnvelope(rawPayload, "naver");
  const rows = arrayAtKnownPath(
    payload,
    options.arrayPaths ?? SCHEDULE_ARRAY_PATHS,
    "naver",
    "schedule"
  );
  const games = [];
  const anomalies = [];
  for (const [index, row] of rows.slice(0, options.maxGames ?? 10).entries()) {
    try {
      const game = mapScheduleRow(row, targetDate, index);
      games.push(game);
      anomalies.push(...scheduleFieldAnomalies(game, "naver", index));
    } catch (error) {
      anomalies.push({
        provider: "naver",
        type: "INVALID_SCHEDULE_ROW",
        row: index,
        message: error.message,
      });
    }
  }
  if (rows.length > 0 && games.length === 0) {
    throw new ProviderSchemaError("naver", "모든 schedule 행이 canonical 검증에 실패했습니다.", {
      anomalies,
    });
  }
  return { games, anomalies };
}

function eventRows(payload, options) {
  const directPaths = options.eventArrayPaths ?? EVENT_ARRAY_PATHS;
  for (const candidate of directPaths) {
    const value = getPath(payload, candidate);
    if (Array.isArray(value)) return value;
  }
  const inningPaths = options.inningArrayPaths ?? INNING_ARRAY_PATHS;
  for (const candidate of inningPaths) {
    const innings = getPath(payload, candidate);
    if (!Array.isArray(innings)) continue;
    return innings.flatMap((inning) => {
      const plays = firstPresent(inning, ["plays", "events", "playByPlay"]);
      if (!Array.isArray(plays)) return [];
      return plays.map((play) => ({
        ...play,
        inning: firstPresent(play, ["inning", "inningNo"]) ??
          firstPresent(inning, ["inning", "inningNo", "number"]),
        half: firstPresent(play, ["half", "topBottom", "isTop"]) ??
          firstPresent(inning, ["half", "topBottom", "isTop"]),
      }));
    });
  }
  return [];
}

function detailRoot(payload) {
  return getPath(payload, "result") ?? getPath(payload, "data") ?? payload;
}

export function parseNaverDetail(rawPayload, game, options = {}) {
  const payload = parseJsonEnvelope(rawPayload, "naver");
  const root = detailRoot(payload);
  const knownEventPaths = options.eventArrayPaths ?? EVENT_ARRAY_PATHS;
  const knownInningPaths = options.inningArrayPaths ?? INNING_ARRAY_PATHS;
  const hasKnownField = DETAIL_FIELD_PATHS.some(
    (candidate) => getPath(root, candidate) !== undefined
  );
  const hasKnownArray = [...knownEventPaths, ...knownInningPaths].some(
    (candidate) => Array.isArray(getPath(payload, candidate))
  );
  if (!hasKnownField && !hasKnownArray) {
    throw new ProviderSchemaError(
      "naver",
      "detail 응답에서 알려진 상태·점수·이닝·이벤트 필드를 찾지 못했습니다.",
      {
        expectedFields: DETAIL_FIELD_PATHS,
        expectedArrayPaths: [...knownEventPaths, ...knownInningPaths],
      }
    );
  }
  const rawEvents = eventRows(payload, options);
  const maximumEvents = options.maxEvents ?? 30;
  const selectedEvents = maximumEvents === 0
    ? []
    : rawEvents.slice(-maximumEvents);
  const events = selectedEvents
    .map((raw, index) => {
      const event = normalizeEvent({
        ...raw,
        sequence: firstPresent(raw, ["sequence", "seq", "id"]) ?? index,
      });
      if (!options.includeDescriptions) event.description = null;
      return event;
    });

  const rawPatch = {
    status: firstPresent(root, ["status", "gameState", "state", "statusType"]),
    statusText: text(firstPresent(root, ["statusText", "message", "description"])),
    startedAt: scheduledValue(
      firstPresent(root, ["startTime", "startedAt", "actualStartTime"]),
      game.date
    ),
    endedAt: scheduledValue(
      firstPresent(root, ["endTime", "endedAt", "actualEndTime"]),
      game.date
    ),
    inning: firstPresent(root, ["inning", "currentInning", "round"]),
    half: firstPresent(root, ["half", "inningHalf", "isTop", "topBottom"]),
    score: {
      home: firstPresent(root, ["homeScore", "homeTeam.score", "home.score", "score.home"]),
      away: firstPresent(root, ["awayScore", "awayTeam.score", "away.score", "score.away"]),
    },
    ...(events.length > 0 ? { events } : {}),
    meta: { provenance: [{ provider: "naver", detail: options.kind ?? "relay" }] },
  };
  const patch = normalizeGamePatch(rawPatch);
  const recordComplete =
    options.kind === "record" &&
    patch.status === "FINISHED" &&
    Number.isInteger(patch.score?.home) &&
    Number.isInteger(patch.score?.away);
  return { patch, recordComplete };
}

export async function fetchNaverSchedule({ dateKey, config, http }) {
  const provider = config.providers.naver;
  const url = buildUrl(provider.schedule.urlTemplate, {
    date: dateKey,
    dateYYYYMMDD: dateCompact(dateKey),
    dateYYYY_MM_DD: dateKey,
  });
  const payload = await http.requestJson("naver", "schedule", url);
  return parseNaverSchedule(payload, dateKey, {
    maxGames: config.run.maxGamesPerRun,
    arrayPaths: provider.schedule.arrayPaths,
  });
}

export async function fetchNaverDetail({ game, config, http }) {
  const provider = config.providers.naver;
  const gameId = game.externalId?.naver;
  if (!gameId) {
    throw new ProviderSchemaError("naver", "상세 조회에 필요한 gameId가 없습니다.");
  }
  const isFinished = game.status === "FINISHED";
  const endpoint = isFinished ? provider.record : provider.relay;
  const capability = isFinished ? "record" : "relay";
  if (config.data?.includePlayDescriptions === true) {
    assertProviderCapability(config, "naver", "relay-text");
  }
  const url = buildUrl(endpoint.urlTemplate, { gameId });
  const payload = await http.requestJson("naver", capability, url);
  return parseNaverDetail(payload, game, {
    kind: capability,
    maxEvents:
      config.data?.maxEventsPerGame ?? 30,
    includeDescriptions: config.data?.includePlayDescriptions === true,
    eventArrayPaths: provider.keys.relayPlayCandidates,
    inningArrayPaths: provider.keys.inningArrayCandidates,
  });
}
