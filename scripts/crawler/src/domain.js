/**
 * Provider-independent game domain values.
 *
 * A GameSnapshot always has this shape:
 * `{ date, gameNumber, source, externalId, scheduledAt, startedAt, endedAt,
 * homeTeam, awayTeam, stadium, status, statusText, score, inning, half,
 * events, meta }`. Unknown scalar values are `null` (except status, which is
 * `UNKNOWN`) so callers never have to branch on provider-specific score fields.
 */

export const GAME_STATUSES = Object.freeze([
  "UNKNOWN",
  "SCHEDULED",
  "LIVE",
  "FINISHED",
  "CANCELLED",
  "POSTPONED",
  "DELAYED",
  "SUSPENDED",
]);

export const INNING_HALVES = Object.freeze(["TOP", "BOTTOM"]);

export const EVENT_TYPES = Object.freeze([
  "HOME_RUN",
  "TRIPLE",
  "DOUBLE",
  "SINGLE",
  "WALK",
  "HIT_BY_PITCH",
  "STRIKEOUT",
  "STOLEN_BASE",
  "CAUGHT_STEALING",
  "DOUBLE_PLAY",
  "ERROR",
  "SACRIFICE",
  "OUT",
  "RUN",
  "OTHER",
]);

const DEFAULT_TIMEZONE_OFFSET_MINUTES = 9 * 60;

const TEAM_CATALOG = Object.freeze([
  {
    code: "KIA",
    name: "KIA",
    aliases: ["KIA", "KIA타이거즈", "기아", "기아타이거즈", "HT"],
  },
  {
    code: "DOO",
    name: "두산",
    aliases: ["DOO", "DOOSAN", "두산", "두산베어스", "OB"],
  },
  {
    code: "LG",
    name: "LG",
    aliases: ["LG", "LG트윈스", "엘지", "엘지트윈스"],
  },
  {
    code: "KT",
    name: "KT",
    aliases: ["KT", "KTWIZ", "KT위즈", "케이티", "케이티위즈"],
  },
  {
    code: "WOE",
    name: "키움",
    aliases: ["WOE", "WO", "KIWOOM", "키움", "키움히어로즈", "넥센", "넥센히어로즈"],
  },
  {
    code: "SS",
    name: "삼성",
    aliases: ["SS", "SAMSUNG", "삼성", "삼성라이온즈"],
  },
  {
    code: "LT",
    name: "롯데",
    aliases: ["LT", "LOTTE", "롯데", "롯데자이언츠"],
  },
  {
    code: "NC",
    name: "NC",
    aliases: ["NC", "NC다이노스", "엔씨", "엔씨다이노스"],
  },
  {
    code: "HH",
    name: "한화",
    aliases: ["HH", "HANWHA", "한화", "한화이글스"],
  },
  {
    code: "SSG",
    name: "SSG",
    aliases: ["SSG", "SSG랜더스", "에스에스지", "에스에스지랜더스", "SK", "SK와이번스"],
  },
]);

const TEAM_BY_ALIAS = new Map();
for (const team of TEAM_CATALOG) {
  TEAM_BY_ALIAS.set(compactKey(team.code), team);
  TEAM_BY_ALIAS.set(compactKey(team.name), team);
  for (const alias of team.aliases) TEAM_BY_ALIAS.set(compactKey(alias), team);
}

/** Return trimmed, whitespace-collapsed text or `null`. */
export function normalizeText(value) {
  if (value == null || typeof value === "object") return null;
  const text = String(value).replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
  return text || null;
}

/** Only non-negative integral scores are accepted. Text such as `3점` is rejected. */
export function normalizeScore(value) {
  if (typeof value === "number") {
    return Number.isSafeInteger(value) && value >= 0 ? value : null;
  }
  const text = normalizeText(value);
  if (!text || !/^\d+$/.test(text)) return null;
  const score = Number(text);
  return Number.isSafeInteger(score) ? score : null;
}

/** Normalize a calendar date to YYYY-MM-DD without JavaScript date rollover. */
export function normalizeDate(value) {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : dateAtOffset(value, DEFAULT_TIMEZONE_OFFSET_MINUTES);
  }

  const text = normalizeText(value);
  if (!text) return null;

  const match = text.match(/^(\d{4})\s*(?:[-./년]?\s*)(\d{1,2})\s*(?:[-./월]?\s*)(\d{1,2})(?:\s*일)?(?:$|[T\s])/);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (!isValidCalendarDate(year, month, day)) return null;
  return `${pad(year, 4)}-${pad(month)}-${pad(day)}`;
}

/**
 * Normalize an instant to ISO-8601 UTC. Provider timestamps without an offset
 * are interpreted as KST (+09:00) unless `timezoneOffsetMinutes` is supplied.
 */
export function normalizeDateTime(
  value,
  fallbackDate = null,
  { timezoneOffsetMinutes = DEFAULT_TIMEZONE_OFFSET_MINUTES } = {}
) {
  if (value instanceof Date) {
    return Number.isNaN(value.getTime()) ? null : value.toISOString();
  }

  if (typeof value === "number") {
    if (!Number.isFinite(value)) return null;
    const digits = String(Math.trunc(value));
    // Provider feeds commonly encode 20260825 / 202608251830 as numbers.
    if (/^\d{8}(?:\d{4}(?:\d{2})?)?$/.test(digits)) {
      value = digits;
    } else {
      const millis = value >= 1_000_000_000 && value < 10_000_000_000
        ? value * 1_000
        : value;
      const date = new Date(millis);
      return !Number.isNaN(date.getTime()) ? date.toISOString() : null;
    }
  }

  const text = normalizeText(value);
  if (!text) return null;

  const timeOnly = text.match(/^(\d{1,2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/);
  if (timeOnly) {
    const date = normalizeDate(fallbackDate);
    if (!date) return null;
    const [year, month, day] = date.split("-").map(Number);
    return componentsToIso(
      year,
      month,
      day,
      Number(timeOnly[1]),
      Number(timeOnly[2]),
      Number(timeOnly[3] ?? 0),
      Number((timeOnly[4] ?? "0").padEnd(3, "0")),
      timezoneOffsetMinutes
    );
  }

  const compact = text.match(/^(\d{4})(\d{2})(\d{2})(?:[T\s]?(\d{2})(\d{2})(?:(\d{2}))?)?$/);
  if (compact) {
    return componentsToIso(
      Number(compact[1]),
      Number(compact[2]),
      Number(compact[3]),
      Number(compact[4] ?? 0),
      Number(compact[5] ?? 0),
      Number(compact[6] ?? 0),
      0,
      timezoneOffsetMinutes
    );
  }

  const localDateTime = text.match(
    /^(\d{4})[-./](\d{1,2})[-./](\d{1,2})(?:[T\s]+(\d{1,2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?)?$/
  );
  if (localDateTime) {
    return componentsToIso(
      Number(localDateTime[1]),
      Number(localDateTime[2]),
      Number(localDateTime[3]),
      Number(localDateTime[4] ?? 0),
      Number(localDateTime[5] ?? 0),
      Number(localDateTime[6] ?? 0),
      Number((localDateTime[7] ?? "0").padEnd(3, "0")),
      timezoneOffsetMinutes
    );
  }

  // Only delegate explicitly zoned timestamps to Date.parse.
  if (/^\d{4}-\d{2}-\d{2}T/.test(text) && /(?:Z|[+-]\d{2}:?\d{2})$/i.test(text)) {
    if (!normalizeDate(text)) return null;
    const millis = Date.parse(text);
    return Number.isNaN(millis) ? null : new Date(millis).toISOString();
  }

  return null;
}

/** Map provider-specific game states to the canonical status enum. */
export function normalizeStatus(value) {
  const raw = value && typeof value === "object"
    ? firstValue(value, ["code", "status", "state", "name", "text"])
    : value;
  const text = normalizeText(raw);
  if (!text) return "UNKNOWN";
  const key = compactKey(text);

  const exact = {
    UNKNOWN: "UNKNOWN",
    SCHEDULED: "SCHEDULED",
    READY: "SCHEDULED",
    BEFORE: "SCHEDULED",
    LIVE: "LIVE",
    INPROGRESS: "LIVE",
    PLAYING: "LIVE",
    FINISHED: "FINISHED",
    FINAL: "FINISHED",
    END: "FINISHED",
    ENDED: "FINISHED",
    CANCELLED: "CANCELLED",
    CANCELED: "CANCELLED",
    POSTPONED: "POSTPONED",
    DELAYED: "DELAYED",
    SUSPENDED: "SUSPENDED",
  };
  if (exact[key]) return exact[key];

  if (/(취소|노게임)/.test(text)) return "CANCELLED";
  if (/(종료|경기끝|완료|콜드게임)/.test(text)) return "FINISHED";
  if (/(중단|서스펜디드)/.test(text)) return "SUSPENDED";
  if (/(연기|순연)/.test(text)) return "POSTPONED";
  if (/(지연|대기중|우천대기)/.test(text)) return "DELAYED";
  if (/(경기중|진행중|중계중|현재)/.test(text)) return "LIVE";
  if (/(예정|경기전|시작전|미정)/.test(text)) return "SCHEDULED";
  return "UNKNOWN";
}

/** Normalize top/bottom values, including boolean `isTop` fields. */
export function normalizeHalf(value) {
  if (value === true || value === 1) return "TOP";
  if (value === false || value === 0) return "BOTTOM";
  const text = normalizeText(value);
  if (!text) return null;
  const key = compactKey(text);
  if (["TOP", "T", "초", "상", "전반", "TRUE", "1"].includes(key)) return "TOP";
  if (["BOTTOM", "BOT", "B", "말", "하", "후반", "FALSE", "0"].includes(key)) return "BOTTOM";
  if (/^(?:TOP|초)\d+$/i.test(key) || /^\d+(?:회)?초$/.test(key)) return "TOP";
  if (/^(?:BOTTOM|BOT|말)\d+$/i.test(key) || /^\d+(?:회)?말$/.test(key)) return "BOTTOM";
  return null;
}

/** Return `{ number, half }` for values such as 7, `7회초`, `7초`, or `{ isTop: false }`. */
export function normalizeInning(value, halfValue = undefined) {
  let inningValue = value;
  let embeddedHalf;
  if (value && typeof value === "object" && !Array.isArray(value)) {
    inningValue = firstValue(value, ["number", "inning", "inningNo", "no", "round"]);
    embeddedHalf = firstValue(value, ["half", "isTop", "topBottom"]);
  }

  let number = null;
  let parsedHalf = null;
  if (typeof inningValue === "number") {
    number = normalizePositiveInteger(inningValue);
  } else {
    const text = normalizeText(inningValue);
    if (text) {
      const combined = text.match(/^(\d+)(?:\s*(?:회|이닝|INNING|TH))?\s*(초|말|TOP|BOTTOM|BOT)?$/i)
        ?? text.match(/^(초|말|TOP|BOTTOM|BOT)\s*(\d+)$/i);
      if (combined) {
        const leadingHalf = /^(초|말|TOP|BOTTOM|BOT)$/i.test(combined[1] ?? "") ? combined[1] : null;
        const numberPart = leadingHalf ? combined[2] : combined[1];
        const trailingHalf = leadingHalf ? null : combined[2];
        number = normalizePositiveInteger(numberPart);
        parsedHalf = normalizeHalf(leadingHalf ?? trailingHalf);
      }
    }
  }

  const explicitHalf = normalizeHalf(halfValue);
  return {
    number,
    half: explicitHalf ?? normalizeHalf(embeddedHalf) ?? parsedHalf,
  };
}

/** Normalize KBO team aliases to a stable code and short display name. */
export function normalizeTeam(value) {
  let rawName = value;
  let rawCode = null;
  if (value && typeof value === "object" && !Array.isArray(value)) {
    rawName = firstValue(value, ["name", "teamName", "displayName", "shortName"]);
    rawCode = firstValue(value, ["code", "teamCode", "id"]);
  }

  const name = normalizeText(rawName);
  const code = normalizeText(rawCode);
  const catalogTeam = (name ? TEAM_BY_ALIAS.get(compactKey(name)) : null)
    ?? (code ? TEAM_BY_ALIAS.get(compactKey(code)) : null);
  if (catalogTeam) return { code: catalogTeam.code, name: catalogTeam.name };

  const safeCode = code && /^[A-Za-z0-9_-]{1,16}$/.test(code) ? code.toUpperCase() : null;
  return { code: safeCode, name };
}

/** Stable key used for cross-provider team matching. */
export function teamKey(value) {
  const team = normalizeTeam(value);
  if (team.code && TEAM_BY_ALIAS.has(compactKey(team.code))) return team.code;
  return team.name ? compactKey(team.name) : null;
}

/** Classify a provider event type or its natural-language description. */
export function normalizeEventType(value, description = null) {
  const explicit = normalizeText(value);
  if (explicit) {
    const key = compactKey(explicit);
    const exact = {
      HOMERUN: "HOME_RUN",
      HR: "HOME_RUN",
      TRIPLE: "TRIPLE",
      DOUBLE: "DOUBLE",
      SINGLE: "SINGLE",
      HIT: "SINGLE",
      WALK: "WALK",
      BB: "WALK",
      HITBYPITCH: "HIT_BY_PITCH",
      HBP: "HIT_BY_PITCH",
      STRIKEOUT: "STRIKEOUT",
      SO: "STRIKEOUT",
      STOLENBASE: "STOLEN_BASE",
      SB: "STOLEN_BASE",
      CAUGHTSTEALING: "CAUGHT_STEALING",
      CS: "CAUGHT_STEALING",
      DOUBLEPLAY: "DOUBLE_PLAY",
      DP: "DOUBLE_PLAY",
      ERROR: "ERROR",
      SACRIFICE: "SACRIFICE",
      OUT: "OUT",
      RUN: "RUN",
      OTHER: "OTHER",
    };
    if (exact[key] && exact[key] !== "OTHER") return exact[key];
  }

  const text = normalizeText(description) ?? explicit;
  if (!text) return "OTHER";
  if (/(홈런|HOME\s*RUN)/i.test(text)) return "HOME_RUN";
  // `3루수`, `2루수` are fielding positions. Only the hit-result suffix `루타`
  // denotes a triple/double, preventing common Korean relay misclassification.
  if (/(3루타|THREE[- ]?BASE\s+HIT|TRIPLE)/i.test(text)) return "TRIPLE";
  if (/(2루타|TWO[- ]?BASE\s+HIT|DOUBLE)/i.test(text)) return "DOUBLE";
  if (/(1루타|내야안타|번트안타|안타|SINGLE)/i.test(text)) return "SINGLE";
  if (/(도루실패|도루자|CAUGHT\s+STEALING)/i.test(text)) return "CAUGHT_STEALING";
  if (/(도루|STOLEN\s+BASE)/i.test(text)) return "STOLEN_BASE";
  if (/(몸에\s*맞는\s*공|사구|HIT\s+BY\s+PITCH)/i.test(text)) return "HIT_BY_PITCH";
  if (/(볼넷|고의4구|고의사구|WALK)/i.test(text)) return "WALK";
  if (/(삼진|STRIKEOUT)/i.test(text)) return "STRIKEOUT";
  if (/(병살|DOUBLE\s+PLAY)/i.test(text)) return "DOUBLE_PLAY";
  if (/(실책|오류|ERROR)/i.test(text)) return "ERROR";
  if (/(희생번트|희생플라이|희생타|SACRIFICE)/i.test(text)) return "SACRIFICE";
  if (/(득점|홈인|SCORES?)/i.test(text)) return "RUN";
  if (/(아웃|땅볼|뜬공|플라이|직선타|파울플라이|OUT)/i.test(text)) return "OUT";
  return "OTHER";
}

/** Normalize one play/relay event. */
export function normalizeEvent(raw = {}) {
  const input = raw && typeof raw === "object" ? raw : { description: raw };
  const description = normalizeText(firstValue(input, [
    "description",
    "resultText",
    "result",
    "playResult",
    "scoreText",
    "text",
  ]));
  const inning = normalizeInning(
    firstValue(input, ["inning", "inningNo", "inn", "round"]),
    firstValue(input, ["half", "isTop", "topBottom"])
  );
  const runs = normalizeScore(firstValue(input, ["runs", "run", "rbi", "runCount"]));
  return {
    id: normalizeText(firstValue(input, ["id", "eventId", "playId"])),
    inning: inning.number,
    half: inning.half,
    batter: normalizeText(firstValue(input, ["batter", "batterName", "hitter", "playerName"])),
    pitcher: normalizeText(firstValue(input, ["pitcher", "pitcherName"])),
    eventType: normalizeEventType(firstValue(input, ["eventType", "type", "playType"]), description),
    description,
    runs,
  };
}

/**
 * Convert any supported provider record to the one canonical GameSnapshot form.
 * `options.source` may be `kbo` or `naver` when the raw record has no source.
 */
export function normalizeGameSnapshot(raw = {}, options = {}) {
  const input = raw && typeof raw === "object" ? raw : {};
  const timezoneOffsetMinutes = options.timezoneOffsetMinutes ?? DEFAULT_TIMEZONE_OFFSET_MINUTES;
  const rawDate = firstValue(input, ["date", "gameDate", "matchDate"]);
  let date = normalizeDate(rawDate);
  const scheduledValue = firstValue(input, ["scheduledAt", "gameDateTime", "scheduledTime", "gameTime"]);
  const scheduledAt = normalizeDateTime(scheduledValue, date, { timezoneOffsetMinutes });
  if (!date && scheduledAt) date = dateAtOffset(new Date(scheduledAt), timezoneOffsetMinutes);

  const inning = normalizeInning(
    firstValue(input, ["inning", "currentInning", "inningText", "round"]),
    firstValue(input, ["half", "isTop", "inningHalf", "topBottom"])
  );
  const statusValue = firstValue(input, ["status", "gameState", "matchState", "state", "statusType"]);
  const eventsValue = firstValue(input, ["events", "plays", "playByPlay"]);

  return {
    date,
    gameNumber: normalizePositiveInteger(firstValue(input, [
      "gameNumber",
      "doubleHeaderNo",
      "doubleheaderNo",
      "dhNo",
      "sequence",
      "meta.gameNumber",
    ])),
    source: normalizeSource(input.source, options.source),
    externalId: normalizeExternalId(input, options.source),
    scheduledAt,
    startedAt: normalizeDateTime(firstValue(input, ["startedAt", "startTime", "gameStart"]), date, { timezoneOffsetMinutes }),
    endedAt: normalizeDateTime(firstValue(input, ["endedAt", "endTime", "gameEnd"]), date, { timezoneOffsetMinutes }),
    homeTeam: normalizeTeam(firstValue(input, ["homeTeam", "homeTeamName", "homeName"])),
    awayTeam: normalizeTeam(firstValue(input, ["awayTeam", "awayTeamName", "awayName"])),
    stadium: normalizeText(firstValue(input, ["stadium", "stadiumName", "place", "ground"])),
    status: normalizeStatus(statusValue),
    statusText: normalizeText(firstValue(input, ["statusText", "gameStateText", "matchStatus"]))
      ?? normalizeText(statusValue),
    score: {
      home: normalizeScore(firstValue(input, ["score.home", "homeScore", "homeTeam.score"])),
      away: normalizeScore(firstValue(input, ["score.away", "awayScore", "awayTeam.score"])),
    },
    inning: inning.number,
    half: inning.half,
    events: Array.isArray(eventsValue) ? eventsValue.map(normalizeEvent) : [],
    meta: isPlainObject(input.meta) ? clonePlainObject(input.meta) : {},
  };
}

/** Normalize only fields actually present in a partial provider response. */
export function normalizeGamePatch(raw = {}, options = {}) {
  const input = raw && typeof raw === "object" ? raw : {};
  const canonical = normalizeGameSnapshot(input, options);
  const patch = {};

  if (hasAnyPath(input, ["date", "gameDate", "matchDate"]) && canonical.date) patch.date = canonical.date;
  if (hasAnyPath(input, ["gameNumber", "doubleHeaderNo", "doubleheaderNo", "dhNo", "sequence", "meta.gameNumber"]) && canonical.gameNumber) {
    patch.gameNumber = canonical.gameNumber;
  }
  if (hasAnyPath(input, ["source"]) || options.source) patch.source = canonical.source;
  if (hasAnyPath(input, ["externalId", "kboGameId", "naverGameId", "gameId", "id"])) patch.externalId = canonical.externalId;
  if (hasAnyPath(input, ["scheduledAt", "gameDateTime", "scheduledTime", "gameTime"]) && canonical.scheduledAt) patch.scheduledAt = canonical.scheduledAt;
  if (hasAnyPath(input, ["startedAt", "startTime", "gameStart"]) && canonical.startedAt) patch.startedAt = canonical.startedAt;
  if (hasAnyPath(input, ["endedAt", "endTime", "gameEnd"]) && canonical.endedAt) patch.endedAt = canonical.endedAt;
  if (hasAnyPath(input, ["homeTeam", "homeTeamName", "homeName"])) patch.homeTeam = compactTeam(canonical.homeTeam);
  if (hasAnyPath(input, ["awayTeam", "awayTeamName", "awayName"])) patch.awayTeam = compactTeam(canonical.awayTeam);
  if (hasAnyPath(input, ["stadium", "stadiumName", "place", "ground"]) && canonical.stadium) patch.stadium = canonical.stadium;
  if (hasAnyPath(input, ["status", "gameState", "matchState", "state", "statusType"]) && canonical.status !== "UNKNOWN") patch.status = canonical.status;
  if (hasAnyPath(input, ["statusText", "gameStateText", "matchStatus"]) && isUsefulValue(canonical.statusText)) patch.statusText = canonical.statusText;

  const hasHomeScore = hasAnyPath(input, ["score.home", "homeScore", "homeTeam.score"]);
  const hasAwayScore = hasAnyPath(input, ["score.away", "awayScore", "awayTeam.score"]);
  if (hasHomeScore || hasAwayScore) {
    patch.score = {};
    if (hasHomeScore && canonical.score.home !== null) patch.score.home = canonical.score.home;
    if (hasAwayScore && canonical.score.away !== null) patch.score.away = canonical.score.away;
    if (Object.keys(patch.score).length === 0) delete patch.score;
  }

  if (hasAnyPath(input, ["inning", "currentInning", "inningText", "round"]) && canonical.inning !== null) patch.inning = canonical.inning;
  if (hasAnyPath(input, ["half", "isTop", "inningHalf", "topBottom", "inning"]) && canonical.half !== null) patch.half = canonical.half;
  if (hasAnyPath(input, ["events", "plays", "playByPlay"]) && canonical.events.length > 0) patch.events = canonical.events;
  if (isPlainObject(input.meta)) patch.meta = clonePlainObject(input.meta);
  return patch;
}

/**
 * Apply a conservative patch. `null`, empty text, `UNKNOWN`, and empty arrays
 * never erase an existing useful value; numeric zero and boolean false remain valid.
 */
export function applyGamePatch(base, rawPatch, options = {}) {
  const current = normalizeGameSnapshot(base, options);
  const patch = normalizeGamePatch(rawPatch, options);
  const merged = mergeUseful(current, patch);

  // Provenance is additive: observing a provider can never un-observe another.
  merged.source = {
    kbo: Boolean(current.source.kbo || patch.source?.kbo),
    naver: Boolean(current.source.naver || patch.source?.naver),
  };
  return normalizeGameSnapshot(merged, options);
}

function normalizeSource(value, fallbackSource) {
  const source = { kbo: false, naver: false };
  const add = (entry) => {
    const key = compactKey(entry);
    if (key === "KBO") source.kbo = true;
    if (key === "NAVER") source.naver = true;
    if (key === "HYBRID") {
      source.kbo = true;
      source.naver = true;
    }
  };

  if (typeof value === "string") add(value);
  if (Array.isArray(value)) value.forEach(add);
  if (isPlainObject(value)) {
    source.kbo = value.kbo === true;
    source.naver = value.naver === true;
  }
  if (fallbackSource) add(fallbackSource);
  return source;
}

function normalizeExternalId(input, sourceHint) {
  const raw = isPlainObject(input.externalId) ? input.externalId : {};
  let kbo = normalizeText(firstValue(input, ["externalId.kbo", "kboGameId"]));
  let naver = normalizeText(firstValue(input, ["externalId.naver", "naverGameId"]));
  const generic = normalizeText(firstValue(input, ["gameId", "id"]));
  const source = compactKey(sourceHint ?? (typeof input.source === "string" ? input.source : ""));
  if (!kbo && source === "KBO") kbo = generic;
  if (!naver && source === "NAVER") naver = generic;
  return {
    kbo: kbo ?? normalizeText(raw.kbo),
    naver: naver ?? normalizeText(raw.naver),
  };
}

function compactTeam(team) {
  const result = {};
  if (team.code) result.code = team.code;
  if (team.name) result.name = team.name;
  return result;
}

function mergeUseful(base, patch) {
  if (!isPlainObject(patch)) return cloneValue(base);
  const output = isPlainObject(base) ? clonePlainObject(base) : {};
  for (const [key, patchValue] of Object.entries(patch)) {
    const baseValue = output[key];
    if (!isUsefulValue(patchValue, baseValue)) continue;
    if (isPlainObject(patchValue)) {
      output[key] = mergeUseful(isPlainObject(baseValue) ? baseValue : {}, patchValue);
    } else {
      output[key] = cloneValue(patchValue);
    }
  }
  return output;
}

function isUsefulValue(value, existingValue = undefined) {
  if (value == null) return false;
  if (typeof value === "string") {
    const text = value.trim();
    if (!text) return false;
    if (text.toUpperCase() === "UNKNOWN" && isUsefulValue(existingValue)) return false;
  }
  if (Array.isArray(value) && value.length === 0 && Array.isArray(existingValue) && existingValue.length > 0) return false;
  return true;
}

function cloneValue(value) {
  if (Array.isArray(value)) return value.map(cloneValue);
  if (isPlainObject(value)) return clonePlainObject(value);
  return value;
}

function clonePlainObject(value) {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, cloneValue(item)]));
}

function firstValue(object, paths) {
  for (const path of paths) {
    const value = valueAtPath(object, path);
    if (value !== undefined && value !== null && !(typeof value === "string" && value.trim() === "")) return value;
  }
  return undefined;
}

function valueAtPath(object, path) {
  let current = object;
  for (const part of path.split(".")) {
    if (current == null || !Object.prototype.hasOwnProperty.call(Object(current), part)) return undefined;
    current = current[part];
  }
  return current;
}

function hasAnyPath(object, paths) {
  return paths.some((path) => valueAtPath(object, path) !== undefined);
}

function normalizePositiveInteger(value) {
  if (typeof value === "number") return Number.isSafeInteger(value) && value > 0 ? value : null;
  const text = normalizeText(value);
  if (!text || !/^\d+$/.test(text)) return null;
  const number = Number(text);
  return Number.isSafeInteger(number) && number > 0 ? number : null;
}

function compactKey(value) {
  return String(value ?? "").normalize("NFKC").replace(/[^0-9A-Za-z가-힣]/g, "").toUpperCase();
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value) && !(value instanceof Date);
}

function isValidCalendarDate(year, month, day) {
  if (!Number.isInteger(year) || year < 1900 || year > 2200 || month < 1 || month > 12 || day < 1) return false;
  return day <= new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function componentsToIso(year, month, day, hour, minute, second, millisecond, timezoneOffsetMinutes) {
  if (!isValidCalendarDate(year, month, day)) return null;
  if (![hour, minute, second, millisecond, timezoneOffsetMinutes].every(Number.isFinite)) return null;
  if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59 || millisecond < 0 || millisecond > 999) return null;
  const utcMillis = Date.UTC(year, month - 1, day, hour, minute, second, millisecond)
    - timezoneOffsetMinutes * 60_000;
  const date = new Date(utcMillis);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function dateAtOffset(date, timezoneOffsetMinutes) {
  const shifted = new Date(date.getTime() + timezoneOffsetMinutes * 60_000);
  return `${pad(shifted.getUTCFullYear(), 4)}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}`;
}

function pad(value, length = 2) {
  return String(value).padStart(length, "0");
}
