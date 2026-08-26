import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import cron from "node-cron";
import {
  ConfigError,
  PROJECT_ROOT,
  assertCrawlerPolicy,
  evaluateCrawlerPolicy,
  formatConfigForDisplay,
  formatHelp,
  loadCrawlerConfig,
} from "./config.js";
import { applyGamePatch, normalizeGameSnapshot } from "./domain.js";
import { createHttpCollector } from "./http.js";
import { mergeHybridGames } from "./merge.js";
import {
  assertPolicyAllowed,
  buildPolicyReport,
  formatPolicyReport,
} from "./policy.js";
import { fetchFixtureSchedule } from "./providers/fixture.js";
import { fetchKboSchedule } from "./providers/kbo.js";
import {
  fetchNaverDetail,
  fetchNaverSchedule,
} from "./providers/naver.js";
import {
  recordDetailPoll,
  selectDetailQueue,
  shouldPollSchedule,
} from "./scheduler.js";
import {
  StorageError,
  legacySnapshotContentFingerprint,
  loadCrawlerState,
  readJsonStrict,
  resolveOutputDirectory,
  saveSnapshot,
  snapshotContentFingerprint,
  withRunLock,
} from "./storage.js";

export class CrawlQualityError extends Error {
  constructor(message, details = {}) {
    super(message);
    this.name = "CrawlQualityError";
    this.code = "CRAWL_QUALITY_GATE_FAILED";
    this.details = details;
  }
}

const PROVIDER_STOP_CODES = new Set([
  "ACCESS_DENIED",
  "ATTEMPT_BUDGET_EXHAUSTED",
  "BOT_CHALLENGE",
  "CIRCUIT_OPEN",
  "ENDPOINT_NOT_FOUND",
  "INVALID_JSON_RESPONSE",
  "POLICY_BLOCKED",
  "PROVIDER_SCHEMA_MISMATCH",
  "RATE_LIMITED",
  "REDIRECT_BLOCKED",
  "REQUEST_BUDGET_EXHAUSTED",
  "REQUEST_REJECTED",
  "RESPONSE_TOO_LARGE",
]);
const STRUCTURAL_FAILURE_CODES = new Set([
  "CRAWL_QUALITY_GATE_FAILED",
  "INVALID_JSON_RESPONSE",
  "PROVIDER_SCHEMA_MISMATCH",
]);

function tripOnStructuralFailure(http, providerName, error, config) {
  if (STRUCTURAL_FAILURE_CODES.has(error?.code)) {
    http.tripCircuit?.(
      providerName,
      config.request.circuitBreaker.cooldownMs
    );
  }
}

function todayInTimeZone(timezone, now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const get = (type) => parts.find((part) => part.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

function targetDate(config, now = new Date()) {
  return config.date ?? todayInTimeZone(config.timezone, now);
}

function emptyState() {
  return {
    version: 1,
    snapshots: {},
    polling: {},
    providers: {},
  };
}

function errorSummary(provider, error) {
  return {
    provider,
    code: error?.code ?? "PROVIDER_FETCH_FAILED",
    message: String(error?.message ?? "provider request failed")
      .split("\n", 1)[0]
      .slice(0, 300),
  };
}

function sortGames(games) {
  return [...games].sort((left, right) => {
    const valuesLeft = [
      left.date,
      left.scheduledAt,
      left.homeTeam?.code ?? left.homeTeam?.name,
      left.awayTeam?.code ?? left.awayTeam?.name,
      left.gameNumber ?? 0,
    ];
    const valuesRight = [
      right.date,
      right.scheduledAt,
      right.homeTeam?.code ?? right.homeTeam?.name,
      right.awayTeam?.code ?? right.awayTeam?.name,
      right.gameNumber ?? 0,
    ];
    return valuesLeft.join("|").localeCompare(valuesRight.join("|"));
  });
}

function summarizeGames(games) {
  const statusCounts = {};
  const sourceCounts = {};
  for (const game of games) {
    statusCounts[game.status] = (statusCounts[game.status] ?? 0) + 1;
    const source = game.source?.kbo && game.source?.naver
      ? "hybrid"
      : game.source?.kbo
        ? "kbo"
        : game.source?.naver
          ? "naver"
          : "fixture";
    sourceCounts[source] = (sourceCounts[source] ?? 0) + 1;
  }
  return { total: games.length, statusCounts, sourceCounts };
}

function validateGameQuality(games, config) {
  const invalid = games
    .map((game, index) => ({ game, index }))
    .map(({ game, index }) => {
      const reasons = [];
      if (!game.date) reasons.push("date");
      if (!game.homeTeam?.name) reasons.push("homeTeam");
      if (!game.awayTeam?.name) reasons.push("awayTeam");
      for (const [label, value, maximum] of [
        ["homeTeam.name", game.homeTeam?.name, 100],
        ["awayTeam.name", game.awayTeam?.name, 100],
        ["stadium", game.stadium, 200],
        ["statusText", game.statusText, 200],
        ["externalId.kbo", game.externalId?.kbo, 200],
        ["externalId.naver", game.externalId?.naver, 200],
      ]) {
        if (typeof value === "string" && value.length > maximum) {
          reasons.push(`${label} too long`);
        }
      }
      if (game.events.length > config.data.maxEventsPerGame) {
        reasons.push("events exceed configured maximum");
      }
      if (game.inning !== null && game.inning > 50) {
        reasons.push("inning out of bounds");
      }
      for (const side of ["home", "away"]) {
        if (game.score?.[side] !== null && game.score?.[side] > 999) {
          reasons.push(`score.${side} out of bounds`);
        }
      }
      for (const event of game.events) {
        if (event.id?.length > 200) reasons.push("event.id too long");
        if (event.batter?.length > 100) reasons.push("event.batter too long");
        if (event.pitcher?.length > 100) reasons.push("event.pitcher too long");
        if (event.description?.length > 1_000) {
          reasons.push("event.description too long");
        }
        if (event.inning !== null && event.inning > 50) {
          reasons.push("event.inning out of bounds");
        }
        if (event.runs !== null && event.runs > 999) {
          reasons.push("event.runs out of bounds");
        }
        if (!config.data.includePlayDescriptions && event.description) {
          reasons.push("event.description forbidden by config");
        }
      }
      if (Buffer.byteLength(JSON.stringify(game.meta ?? {}), "utf8") > 20_000) {
        reasons.push("meta too large");
      }
      if (
        game.scheduledAt &&
        todayInTimeZone("Asia/Seoul", new Date(game.scheduledAt)) !== game.date
      ) {
        reasons.push("scheduledAt/date mismatch");
      }
      if (
        game.startedAt &&
        game.endedAt &&
        new Date(game.endedAt).getTime() < new Date(game.startedAt).getTime()
      ) {
        reasons.push("endedAt before startedAt");
      }
      return { index, reasons };
    })
    .filter(({ reasons }) => reasons.length > 0);
  for (const providerName of ["kbo", "naver"]) {
    const indexesById = new Map();
    games.forEach((game, index) => {
      const id = game.externalId?.[providerName];
      if (!id) return;
      const indexes = indexesById.get(id) ?? [];
      indexes.push(index);
      indexesById.set(id, indexes);
    });
    for (const [id, indexes] of indexesById) {
      if (indexes.length > 1) {
        invalid.push({
          index: indexes[0],
          reasons: [`duplicate ${providerName} externalId (${id.slice(0, 40)})`],
        });
      }
    }
  }
  if (invalid.length > 0) {
    throw new CrawlQualityError(
      `${invalid.length}개 경기가 식별·시각 품질 검증에 실패했습니다.`,
      { invalid }
    );
  }
}

async function readPreviousSnapshot(state, dateKey, outputDirectory) {
  const snapshotPath = state.snapshots?.[dateKey]?.path;
  if (!snapshotPath) return null;
  const resolved = path.resolve(snapshotPath);
  const relative = path.relative(path.resolve(outputDirectory), resolved);
  if (relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new StorageError(
      "crawler state가 output 디렉터리 밖의 snapshot을 가리킵니다.",
      "SNAPSHOT_PATH_OUTSIDE_OUTPUT"
    );
  }
  const snapshot = await readJsonStrict(resolved, { fallback: null });
  if (!snapshot) return null;
  const expected = state.snapshots[dateKey].contentFingerprint;
  const actual = snapshotContentFingerprint(snapshot);
  if (snapshot.contentFingerprint !== actual) {
    const legacy = legacySnapshotContentFingerprint(snapshot);
    if (snapshot.contentFingerprint !== legacy) {
      throw new StorageError(
        "이전 snapshot의 content fingerprint가 실제 내용과 일치하지 않습니다.",
        "SNAPSHOT_INTEGRITY_FAILED"
      );
    }
    if (expected !== legacy) {
      throw new StorageError(
        "legacy snapshot과 crawler state의 content fingerprint가 일치하지 않습니다.",
        "SNAPSHOT_STATE_MISMATCH"
      );
    }
    // Keep the legacy state fingerprint. The next successful save observes a
    // changed fingerprint and atomically rewrites both snapshot and state.
    return snapshot;
  }
  if (expected !== actual) {
    // A crash can occur after the atomic snapshot rename but before the state
    // rename. The self-consistent snapshot is the source of truth in that case.
    state.snapshots[dateKey].contentFingerprint = actual;
  }
  return snapshot;
}

async function fetchSchedules({ dateKey, config, http, crawlerRoot }) {
  if (config.source === "fixture") {
    const result = await fetchFixtureSchedule({ dateKey, config, crawlerRoot });
    return {
      ...result,
      partial: (result.anomalies ?? []).length > 0,
      providerErrors: [],
    };
  }
  if (config.source === "kbo") {
    try {
      const result = await fetchKboSchedule({ dateKey, config, http });
      return {
        ...result,
        partial: (result.anomalies ?? []).length > 0,
        providerErrors: [],
      };
    } catch (error) {
      tripOnStructuralFailure(http, "kbo", error, config);
      throw error;
    }
  }
  if (config.source === "naver") {
    try {
      const result = await fetchNaverSchedule({ dateKey, config, http });
      return {
        ...result,
        partial: (result.anomalies ?? []).length > 0,
        providerErrors: [],
      };
    } catch (error) {
      tripOnStructuralFailure(http, "naver", error, config);
      throw error;
    }
  }

  const [kboResult, naverResult] = await Promise.allSettled([
    fetchKboSchedule({ dateKey, config, http }),
    fetchNaverSchedule({ dateKey, config, http }),
  ]);
  const providerErrors = [];
  if (kboResult.status === "rejected") {
    tripOnStructuralFailure(http, "kbo", kboResult.reason, config);
    providerErrors.push(errorSummary("kbo", kboResult.reason));
  }
  if (naverResult.status === "rejected") {
    tripOnStructuralFailure(http, "naver", naverResult.reason, config);
    providerErrors.push(errorSummary("naver", naverResult.reason));
  }
  if (providerErrors.length === 2) {
    throw new AggregateError(
      [kboResult.reason, naverResult.reason],
      "KBO와 Naver schedule 조회가 모두 실패했습니다."
    );
  }

  const kbo = kboResult.status === "fulfilled" ? kboResult.value : {
    games: [],
    anomalies: [],
  };
  const naver = naverResult.status === "fulfilled" ? naverResult.value : {
    games: [],
    anomalies: [],
  };
  const merged = mergeHybridGames(kbo.games, naver.games, {
    leftSource: "kbo",
    rightSource: "naver",
  });
  const anomalies = [
    ...(kbo.anomalies ?? []),
    ...(naver.anomalies ?? []),
    ...merged.anomalies,
  ];
  return {
    games: merged.games,
    anomalies,
    partial: providerErrors.length > 0 || anomalies.length > 0,
    providerErrors,
  };
}

async function enrichDetails({ games, state, config, http, now }) {
  if (!["naver", "hybrid"].includes(config.source)) {
    return { games, errors: [] };
  }
  if (config.run.maxDetailGamesPerRun <= 0) return { games, errors: [] };

  const allowedStates = new Set(config.strategy.detailOnlyStates);
  const eligibleGames = games.filter((game) => allowedStates.has(game.status));
  const queue = selectDetailQueue(eligibleGames, state, config, now.getTime());
  const updatedByNaverId = new Map();
  const errors = [];

  for (const item of queue) {
    const game = item.game;
    if (!game.externalId?.naver) continue;
    try {
      const detail = await fetchNaverDetail({ game, config, http });
      const updated = applyGamePatch(game, detail.patch, { source: "naver" });
      updatedByNaverId.set(game.externalId.naver, updated);
      recordDetailPoll(state, updated, {
        at: now.toISOString(),
        recordComplete: detail.recordComplete,
      });
    } catch (error) {
      tripOnStructuralFailure(http, "naver", error, config);
      errors.push(errorSummary("naver-detail", error));
      recordDetailPoll(state, game, {
        at: now.toISOString(),
        error,
      });
      if (PROVIDER_STOP_CODES.has(error?.code)) {
        break;
      }
    }
  }

  return {
    games: games.map((game) => {
      const naverId = game.externalId?.naver;
      return naverId ? updatedByNaverId.get(naverId) ?? game : game;
    }),
    errors,
  };
}

function makePlan(config, dateKey) {
  const scheduleRequests = config.source === "hybrid"
    ? 2
    : config.source === "fixture"
      ? 0
      : 1;
  const detailRequests = ["naver", "hybrid"].includes(config.source)
    ? config.run.maxDetailGamesPerRun
    : 0;
  return {
    mode: config.run.mode,
    date: dateKey,
    source: config.source,
    externalNetwork: scheduleRequests > 0,
    scheduleRequests,
    detailRequestsAtMost: detailRequests,
    logicalRequestsAtMost: scheduleRequests + detailRequests,
    outputEnabled: config.output.enabled,
    outputDirectory: config.output.directory,
  };
}

function assertAllPolicies(config, now) {
  const configReport = assertCrawlerPolicy(config, { now });
  const sourceReport = assertPolicyAllowed(config, { now });
  return { configReport, sourceReport };
}

export async function runOnce(config, options = {}) {
  const now = options.now ?? new Date();
  const crawlerRoot = options.crawlerRoot ?? PROJECT_ROOT;
  const dateKey = targetDate(config, now);
  const policy = assertAllPolicies(config, now);

  if (config.output.dryRun) {
    return {
      dryRun: true,
      policy,
      plan: makePlan(config, dateKey),
    };
  }

  const outputDirectory = resolveOutputDirectory(config, crawlerRoot);
  const useStorage = config.output.enabled;
  const execute = async () => {
    const loaded = useStorage
      ? await loadCrawlerState(outputDirectory)
      : { state: emptyState(), statePath: null };
    const previous = await readPreviousSnapshot(
      loaded.state,
      dateKey,
      outputDirectory
    );

    if (
      config.run.mode === "scheduled" &&
      config.source !== "fixture" &&
      !shouldPollSchedule({
        state: loaded.state,
        dateKey,
        previousGames: previous?.games ?? [],
        config,
        nowMs: now.getTime(),
      })
    ) {
      return { skipped: true, reason: "adaptive-polling", date: dateKey };
    }

    const http = options.http ?? createHttpCollector(config);
    http.resetRunBudget?.();
    const schedule = await fetchSchedules({
      dateKey,
      config,
      http,
      crawlerRoot,
    });
    try {
      validateGameQuality(schedule.games, config);
    } catch (error) {
      const providers = config.source === "hybrid"
        ? ["kbo", "naver"]
        : [config.source].filter((name) => name !== "fixture");
      for (const providerName of providers) {
        tripOnStructuralFailure(http, providerName, error, config);
      }
      throw error;
    }
    if ((previous?.games?.length ?? 0) > 0 && schedule.games.length === 0) {
      throw new CrawlQualityError(
        "기존 정상 snapshot이 있는데 새 결과가 비어 있어 덮어쓰지 않았습니다.",
        { previousTotal: previous.games.length }
      );
    }

    const detail = await enrichDetails({
      games: schedule.games,
      state: loaded.state,
      config,
      http,
      now,
    });
    const games = sortGames(
      detail.games.map((game) => {
        const normalized = normalizeGameSnapshot(game);
        normalized.meta = {
          ...normalized.meta,
          observedAt: now.toISOString(),
        };
        return normalized;
      })
    );
    const providerErrors = [...schedule.providerErrors, ...detail.errors];
    const snapshot = {
      schemaVersion: 1,
      source: config.source,
      date: dateKey,
      crawledAt: now.toISOString(),
      partial: schedule.partial || providerErrors.length > 0,
      providerErrors,
      anomalies: schedule.anomalies,
      ...summarizeGames(games),
      games,
      requestMetrics: http.getMetrics(),
    };

    if (!useStorage) {
      return { snapshot, saved: false, policy };
    }
    const saved = await saveSnapshot({
      snapshot,
      dateKey,
      config,
      crawlerRoot,
      state: loaded.state,
      statePath: loaded.statePath,
      forceWrite: config.output.forceWrite,
    });
    return { snapshot, saved, policy };
  };

  if (!useStorage) return execute();
  return withRunLock(
    outputDirectory,
    { staleAfterMs: 30 * 60 * 1000 },
    execute
  );
}

export function runScheduled(config, options = {}) {
  assertAllPolicies(config, options.now ?? new Date());
  if (config.date) {
    throw new ConfigError(
      "FIXED_DATE_SCHEDULE_BLOCKED",
      "scheduled 모드에서는 고정 --date를 사용할 수 없습니다. 과거 날짜는 once로 실행하세요."
    );
  }
  if (!cron.validate(config.run.cron)) {
    throw new ConfigError("INVALID_CRON", `잘못된 cron 표현식: ${config.run.cron}`);
  }
  const http = options.http ?? createHttpCollector(config);
  let running = false;
  const task = cron.schedule(
    config.run.cron,
    async () => {
      if (running) {
        console.warn("[crawler] 이전 실행이 진행 중이어서 이번 tick을 건너뜁니다.");
        return;
      }
      running = true;
      try {
        const result = await runOnce(config, { ...options, http, now: new Date() });
        logRunResult(result);
      } catch (error) {
        logError(error, "scheduled run failed");
      } finally {
        running = false;
      }
    },
    {
      timezone: config.timezone,
      noOverlap: true,
    }
  );
  return task;
}

function logRunResult(result) {
  if (result?.dryRun) {
    console.info(JSON.stringify(result.plan, null, 2));
    return;
  }
  if (result?.skipped) {
    console.info(`[crawler] skipped: ${result.reason}, date=${result.date}`);
    return;
  }
  const snapshot = result?.snapshot;
  console.info(
    `[crawler] done: date=${snapshot.date}, total=${snapshot.total}, partial=${snapshot.partial}, saved=${result.saved ? result.saved.changed : false}`
  );
}

function formatConfigPolicy(result) {
  const lines = [`config policy: ${result.ok ? "ALLOW" : "BLOCK"}`];
  for (const entry of result.errors) {
    lines.push(`[BLOCK] ${entry.path} - ${entry.message}`);
  }
  for (const entry of result.warnings) {
    lines.push(`[WARN] ${entry.path} - ${entry.message}`);
  }
  return lines.join("\n");
}

export async function main(argv = process.argv.slice(2), environment = process.env) {
  const loaded = await loadCrawlerConfig({ argv, env: environment });
  const { config, cli } = loaded;

  if (cli.actions.help) {
    console.info(formatHelp());
    return;
  }
  if (cli.actions.printConfig) {
    console.info(formatConfigForDisplay(config));
    console.info(`profile: ${loaded.profile}`);
    console.info(`config: ${loaded.paths.configFile}`);
    console.info(`precedence: ${loaded.precedence.join(" < ")}`);
  }
  if (cli.actions.checkConfig) {
    console.info(`config: OK (profile=${loaded.profile})`);
  }
  if (cli.actions.checkPolicy) {
    const first = evaluateCrawlerPolicy(config);
    const second = buildPolicyReport(config);
    console.info(formatConfigPolicy(first));
    console.info(formatPolicyReport(second));
    if (!first.ok || !second.allowed) {
      throw new ConfigError(
        "POLICY_CHECK_FAILED",
        "정책 검증 결과가 BLOCK입니다. 외부 요청은 실행되지 않았습니다."
      );
    }
  }
  if (
    cli.actions.help ||
    cli.actions.checkConfig ||
    cli.actions.checkPolicy ||
    cli.actions.printConfig
  ) {
    return;
  }

  if (config.run.mode === "scheduled") {
    runScheduled(config);
    console.info(
      `[crawler] schedule started: ${config.run.cron} (${config.timezone})`
    );
    return;
  }
  const result = await runOnce(config);
  logRunResult(result);
}

function logError(error, prefix = "crawler failed") {
  console.error(`[crawler] ${prefix}: ${error?.message ?? error}`);
}

const currentFile = path.resolve(fileURLToPath(import.meta.url));
const invokedFile = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (invokedFile && currentFile.toLowerCase() === invokedFile.toLowerCase()) {
  main().catch((error) => {
    logError(error);
    process.exitCode = error?.code?.includes("POLICY") || error instanceof ConfigError
      ? 2
      : 1;
  });
}
