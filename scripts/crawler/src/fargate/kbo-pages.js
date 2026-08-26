import { load } from "cheerio";
import {
  normalizeGameSnapshot,
  normalizeInning,
  normalizeScore,
  normalizeStatus,
  normalizeTeam,
  normalizeText,
  teamKey,
} from "../domain.js";

const KBO_TEAM_CODES = new Set(["KIA", "DOO", "LG", "KT", "WOE", "SS", "LT", "NC", "HH", "SSG"]);
const TERMINAL = new Set(["FINISHED", "CANCELLED", "POSTPONED"]);

export class KboPageSchemaError extends Error {
  constructor(message, details = {}) {
    super(message);
    this.name = "KboPageSchemaError";
    this.code = "KBO_PAGE_SCHEMA_MISMATCH";
    this.details = details;
  }
}

function clean(value) {
  return String(value ?? "").replace(/\u00a0/g, " ").replace(/\s+/g, " ").trim();
}

function strictTeam(value, context) {
  const team = normalizeTeam(value);
  if (!team.code || !KBO_TEAM_CODES.has(team.code)) {
    throw new KboPageSchemaError(`Unknown KBO team in ${context}: ${clean(value).slice(0, 50)}`);
  }
  return team;
}

function strictDate(year, month, day) {
  const candidate = `${String(year).padStart(4, "0")}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
  const date = new Date(`${candidate}T00:00:00Z`);
  if (
    Number.isNaN(date.getTime())
    || date.getUTCFullYear() !== Number(year)
    || date.getUTCMonth() + 1 !== Number(month)
    || date.getUTCDate() !== Number(day)
  ) {
    throw new KboPageSchemaError(`Invalid KBO page date: ${candidate}`);
  }
  return candidate;
}

function compactDate(dateKey) {
  return dateKey.replaceAll("-", "");
}

function extractGameId(value, dateKey = null) {
  const source = String(value ?? "");
  const match = source.match(/(?:gameId=|\b)(20\d{6}[A-Z0-9]{4,8}\d)(?:\b|&|['"])/i);
  const gameId = match?.[1]?.toUpperCase() ?? null;
  if (gameId && dateKey && !gameId.startsWith(compactDate(dateKey))) return null;
  return gameId;
}

function gameNumberFromId(gameId) {
  if (!gameId) return null;
  const digit = Number(gameId.at(-1));
  return Number.isInteger(digit) && digit > 0 ? digit : null;
}

function scheduleStatus(note, scores, hasReview) {
  const text = clean(note);
  if (/(취소|노게임)/.test(text)) return "CANCELLED";
  if (/(연기|순연)/.test(text)) return "POSTPONED";
  if (/(중단|서스펜디드)/.test(text)) return "SUSPENDED";
  if (/(지연|대기)/.test(text)) return "DELAYED";
  if (scores.away != null && scores.home != null && hasReview) return "FINISHED";
  if (scores.away != null && scores.home != null) return "UNKNOWN";
  return "SCHEDULED";
}

function validateIdentity(game, context) {
  if (!game.date || !game.awayTeam?.code || !game.homeTeam?.code) {
    throw new KboPageSchemaError(`Missing game identity in ${context}`);
  }
  if (game.awayTeam.code === game.homeTeam.code) {
    throw new KboPageSchemaError(`Home and away teams are identical in ${context}`);
  }
  if (game.inning != null && (game.inning < 1 || game.inning > 50)) {
    throw new KboPageSchemaError(`Inning is out of range in ${context}`);
  }
  for (const side of ["away", "home"]) {
    if (game.score[side] != null && game.score[side] > 99) {
      throw new KboPageSchemaError(`Score is out of range in ${context}`);
    }
  }
  return game;
}

export function parseKboSchedulePage(html, targetDate, options = {}) {
  const $ = load(String(html ?? ""));
  const table = $("#tblScheduleList");
  if (table.length !== 1) {
    throw new KboPageSchemaError("KBO schedule table #tblScheduleList was not found exactly once");
  }
  const year = clean($("#ddlYear").val() ?? $("#ddlYear option[selected]").text());
  const month = clean($("#ddlMonth").val() ?? $("#ddlMonth option[selected]").text());
  if (!/^20\d{2}$/.test(year) || !/^\d{1,2}$/.test(month)) {
    throw new KboPageSchemaError("KBO schedule page year/month selectors are missing or invalid");
  }
  const targetMonth = targetDate.slice(0, 7);
  const pageMonth = `${year}-${month.padStart(2, "0")}`;
  if (pageMonth !== targetMonth) {
    throw new KboPageSchemaError(
      `KBO schedule page month ${pageMonth} does not contain target date ${targetDate}`
    );
  }

  const headers = table.find("thead th").toArray().map((cell) => clean($(cell).text()));
  const stadiumHeader = headers.indexOf("구장");
  const noteHeader = headers.indexOf("비고");
  if (stadiumHeader < 0 || noteHeader < 0) {
    throw new KboPageSchemaError("KBO schedule table required headers changed", { headers });
  }

  let currentDate = null;
  const games = [];
  const anomalies = [];
  const rows = table.find("tbody > tr").toArray();
  for (const [rowIndex, element] of rows.entries()) {
    const row = $(element);
    const dayCell = row.children("td.day").first();
    if (dayCell.length) {
      const dayMatch = clean(dayCell.text()).match(/^(\d{1,2})\.(\d{1,2})/);
      if (!dayMatch) {
        throw new KboPageSchemaError(`Invalid date cell at schedule row ${rowIndex}`);
      }
      currentDate = strictDate(year, dayMatch[1], dayMatch[2]);
    }
    if (!currentDate) {
      throw new KboPageSchemaError(`Schedule row ${rowIndex} appears before a date cell`);
    }
    if (currentDate !== targetDate) continue;

    const timeText = clean(row.children("td.time").first().text());
    const play = row.children("td.play").first();
    const teamTexts = play.children("span").toArray().map((cell) => clean($(cell).text()));
    if (teamTexts.length !== 2 || !/^\d{1,2}:\d{2}$/.test(timeText)) {
      anomalies.push({
        type: "INVALID_SCHEDULE_ROW",
        row: rowIndex,
        message: "team or scheduled time structure changed",
      });
      continue;
    }

    try {
      const hasDateCell = dayCell.length === 1;
      const columnOffset = hasDateCell ? 0 : 1;
      const cells = row.children("td").toArray();
      const stadium = clean($(cells[stadiumHeader - columnOffset]).text());
      const note = clean($(cells[noteHeader - columnOffset]).text());
      const scoreValues = play.find("em span").toArray()
        .map((cell) => clean($(cell).text()))
        .filter((value) => /^\d+$/.test(value));
      const score = scoreValues.length === 2
        ? { away: normalizeScore(scoreValues[0]), home: normalizeScore(scoreValues[1]) }
        : { away: null, home: null };
      const linkText = row.find("a[href*='gameId=']").toArray()
        .map((link) => $(link).attr("href"))
        .join(" ");
      const gameId = extractGameId(linkText, targetDate);
      const hasReview = row.find("a").toArray().some((link) => /리뷰/i.test(clean($(link).text())));
      const status = scheduleStatus(note, score, hasReview);
      const game = normalizeGameSnapshot({
        date: targetDate,
        source: { kbo: true, naver: false },
        externalId: { kbo: gameId, naver: null },
        scheduledAt: `${targetDate}T${timeText}:00+09:00`,
        startedAt: null,
        endedAt: null,
        awayTeam: strictTeam(teamTexts[0], `schedule row ${rowIndex}`),
        homeTeam: strictTeam(teamTexts[1], `schedule row ${rowIndex}`),
        stadium: stadium || null,
        status,
        statusText: note && note !== "-" ? note : (status === "SCHEDULED" ? "경기예정" : status),
        score,
        inning: null,
        half: null,
        gameNumber: gameNumberFromId(gameId),
        events: [],
        meta: { provenance: [{ provider: "kbo", page: "schedule", row: rowIndex }] },
      }, { source: "kbo" });
      games.push(validateIdentity(game, `schedule row ${rowIndex}`));
    } catch (error) {
      anomalies.push({
        type: "INVALID_SCHEDULE_ROW",
        row: rowIndex,
        message: String(error.message ?? error).slice(0, 200),
      });
    }
  }
  if (games.length === 0 && anomalies.length > 0) {
    throw new KboPageSchemaError("Every target-date schedule row failed validation", { anomalies });
  }
  if (games.length > (options.maxGames ?? 10)) {
    throw new KboPageSchemaError("KBO schedule game count exceeded configured safety maximum");
  }
  return { games, anomalies, pageDate: pageMonth };
}

function scoreboardStatus(flagText, inning) {
  const normalized = normalizeStatus(flagText);
  if (normalized !== "UNKNOWN") return normalized;
  if (inning.number != null && inning.half != null) return "LIVE";
  if (/(예정|경기전|시작전)/.test(flagText)) return "SCHEDULED";
  return "UNKNOWN";
}

function cardGameId($, card, targetDate) {
  for (const link of card.find("a").toArray()) {
    for (const attribute of ["href", "onclick"]) {
      const gameId = extractGameId($(link).attr(attribute), targetDate);
      if (gameId) return gameId;
    }
  }
  return null;
}

export function parseKboScoreboardPage(html, targetDate, options = {}) {
  const $ = load(String(html ?? ""));
  const record = $("#cphContents_cphContents_cphContents_udpRecord");
  if (record.length !== 1) {
    throw new KboPageSchemaError("KBO scoreboard record container was not found exactly once");
  }
  const dateMatch = clean(record.text()).match(/(20\d{2})[.\/-](\d{2})[.\/-](\d{2})/);
  if (!dateMatch) throw new KboPageSchemaError("KBO scoreboard displayed date was not found");
  const pageDate = strictDate(dateMatch[1], dateMatch[2], dateMatch[3]);
  if (pageDate !== targetDate) {
    throw new KboPageSchemaError(`KBO scoreboard date ${pageDate} does not match target ${targetDate}`);
  }

  const cards = record.find(".smsScore").toArray();
  if (cards.length === 0) {
    if (/경기가\s*(?:없|없습니다)|경기\s*일정이\s*없/i.test(clean(record.text()))) {
      return { games: [], anomalies: [], pageDate };
    }
    throw new KboPageSchemaError("KBO scoreboard contains no .smsScore cards and no no-game marker");
  }
  if (cards.length > (options.maxGames ?? 10)) {
    throw new KboPageSchemaError("KBO scoreboard game count exceeded configured safety maximum");
  }

  const games = [];
  const anomalies = [];
  for (const [cardIndex, element] of cards.entries()) {
    const card = $(element);
    try {
      const awayTeam = strictTeam(card.find(".leftTeam .teamT").first().text(), `scoreboard card ${cardIndex}`);
      const homeTeam = strictTeam(card.find(".rightTeam .teamT").first().text(), `scoreboard card ${cardIndex}`);
      const score = {
        away: normalizeScore(clean(card.find(".leftTeam .score").first().text())),
        home: normalizeScore(clean(card.find(".rightTeam .score").first().text())),
      };
      const pointScores = card.find("table.tScore tbody tr .point").toArray()
        .map((cell) => normalizeScore(clean($(cell).text())));
      if (pointScores.length === 2 && (pointScores[0] !== score.away || pointScores[1] !== score.home)) {
        throw new KboPageSchemaError(`score label/table mismatch in scoreboard card ${cardIndex}`);
      }

      const flagText = clean(card.find(".flag").first().text());
      const inning = normalizeInning(flagText);
      const status = scoreboardStatus(flagText, inning);
      if (status === "UNKNOWN") {
        throw new KboPageSchemaError(`unknown game state in scoreboard card ${cardIndex}: ${flagText}`);
      }
      const place = card.find(".place").first();
      const timeText = clean(place.find("span").first().text());
      const placeText = clean(place.clone().find("span").remove().end().text());
      if (!/^\d{1,2}:\d{2}$/.test(timeText)) {
        throw new KboPageSchemaError(`invalid scheduled time in scoreboard card ${cardIndex}`);
      }
      const gameId = cardGameId($, card, targetDate);
      const hideLiveFields = status !== "LIVE";
      const game = normalizeGameSnapshot({
        date: targetDate,
        source: { kbo: true, naver: false },
        externalId: { kbo: gameId, naver: null },
        scheduledAt: `${targetDate}T${timeText}:00+09:00`,
        // These two pages do not expose trustworthy actual timestamps. Do not infer them.
        startedAt: null,
        endedAt: null,
        awayTeam,
        homeTeam,
        stadium: normalizeText(placeText),
        status,
        statusText: flagText,
        score: status === "SCHEDULED" ? { away: null, home: null } : score,
        inning: hideLiveFields ? null : inning.number,
        half: hideLiveFields ? null : inning.half,
        gameNumber: gameNumberFromId(gameId),
        events: [],
        meta: { provenance: [{ provider: "kbo", page: "scoreboard", card: cardIndex }] },
      }, { source: "kbo" });
      games.push(validateIdentity(game, `scoreboard card ${cardIndex}`));
    } catch (error) {
      anomalies.push({
        type: "INVALID_SCOREBOARD_CARD",
        card: cardIndex,
        message: String(error.message ?? error).slice(0, 200),
      });
    }
  }
  if (games.length === 0) {
    throw new KboPageSchemaError("Every KBO scoreboard card failed validation", { anomalies });
  }
  return { games, anomalies, pageDate };
}

function identityKey(game) {
  const away = teamKey(game.awayTeam);
  const home = teamKey(game.homeTeam);
  return away && home ? `${game.date}|${away}|${home}` : null;
}

function combine(schedule, scoreboard) {
  const scheduleOverridesPregame = ["CANCELLED", "POSTPONED", "DELAYED", "SUSPENDED"].includes(schedule.status)
    && ["UNKNOWN", "SCHEDULED"].includes(scoreboard.status);
  return validateIdentity({
    ...schedule,
    externalId: {
      kbo: scoreboard.externalId?.kbo ?? schedule.externalId?.kbo ?? null,
      naver: null,
    },
    scheduledAt: scoreboard.scheduledAt ?? schedule.scheduledAt,
    stadium: scoreboard.stadium ?? schedule.stadium,
    status: scheduleOverridesPregame || scoreboard.status === "UNKNOWN"
      ? schedule.status
      : scoreboard.status,
    statusText: scheduleOverridesPregame
      ? schedule.statusText
      : (scoreboard.statusText ?? schedule.statusText),
    score: scoreboard.score,
    inning: scoreboard.inning,
    half: scoreboard.half,
    startedAt: scoreboard.startedAt ?? schedule.startedAt,
    endedAt: scoreboard.endedAt ?? schedule.endedAt,
    events: [],
    meta: {
      provenance: [
        ...(schedule.meta?.provenance ?? []),
        ...(scoreboard.meta?.provenance ?? []),
      ],
    },
  }, "merged KBO pages");
}

export function mergeKboPages(scheduleGames = [], scoreboardGames = []) {
  const output = scheduleGames.map((game) => structuredClone(game));
  const matched = new Set();
  const anomalies = [];
  for (const scoreGame of scoreboardGames) {
    const id = scoreGame.externalId?.kbo;
    let candidates = output
      .map((game, index) => ({ game, index }))
      .filter(({ game, index }) => !matched.has(index) && id && game.externalId?.kbo === id);
    if (candidates.length === 0) {
      const key = identityKey(scoreGame);
      candidates = output
        .map((game, index) => ({ game, index }))
        .filter(({ game, index }) => !matched.has(index) && identityKey(game) === key);
      if (candidates.length > 1 && scoreGame.scheduledAt) {
        const sameTime = candidates.filter(({ game }) => game.scheduledAt === scoreGame.scheduledAt);
        if (sameTime.length === 1) candidates = sameTime;
      }
    }
    if (candidates.length === 1) {
      const { index } = candidates[0];
      output[index] = combine(output[index], scoreGame);
      matched.add(index);
      continue;
    }
    if (candidates.length > 1) {
      anomalies.push({
        type: "AMBIGUOUS_PAGE_MATCH",
        game: identityKey(scoreGame),
        message: "scoreboard card matched multiple schedule rows; no guess was made",
      });
      continue;
    }
    anomalies.push({
      type: "SCOREBOARD_ONLY_GAME",
      game: identityKey(scoreGame),
      message: "scoreboard game was absent from the latest schedule page",
    });
    output.push(structuredClone(scoreGame));
  }

  const ids = new Set();
  for (const game of output) {
    const id = game.externalId?.kbo;
    if (id && ids.has(id)) throw new KboPageSchemaError(`Duplicate KBO game id after merge: ${id}`);
    if (id) ids.add(id);
  }
  output.sort((left, right) => [left.scheduledAt, identityKey(left)].join("|")
    .localeCompare([right.scheduledAt, identityKey(right)].join("|")));
  return { games: output, anomalies };
}

export function allGamesTerminal(games) {
  return games.length > 0 && games.every((game) => TERMINAL.has(game.status));
}

export function earliestScheduledAt(games) {
  const values = games
    .filter((game) => !TERMINAL.has(game.status))
    .map((game) => Date.parse(game.scheduledAt))
    .filter(Number.isFinite);
  return values.length > 0 ? Math.min(...values) : null;
}
