import { readFile } from "node:fs/promises";
import axios from "axios";
import { KBO_ENDPOINTS, assertLivePolicy } from "./config.js";
import {
  parseKboScheduleMonthPage,
  parseKboSchedulePage,
  parseKboScheduleResponse,
  parseKboScoreboardPage,
} from "./kbo-pages.js";

export class KboRequestError extends Error {
  constructor(message, code = "KBO_REQUEST_FAILED", details = {}) {
    super(message);
    this.name = "KboRequestError";
    this.code = code;
    this.details = details;
  }
}

function abortableSleep(ms, signal) {
  if (signal?.aborted) return Promise.reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
    }, { once: true });
  });
}

function assertExactEndpoint(capability, rawUrl) {
  const expected = KBO_ENDPOINTS[capability];
  let url;
  try {
    url = new URL(rawUrl);
  } catch (error) {
    throw new KboRequestError("Invalid KBO URL", "INVALID_KBO_URL", { cause: error.message });
  }
  if (
    rawUrl !== expected
    || url.protocol !== "https:"
    || url.hostname !== "www.koreabaseball.com"
    || url.username
    || url.password
    || url.search
    || url.hash
  ) {
    throw new KboRequestError(
      `Only the reviewed KBO ${capability} page URL is allowed`,
      "KBO_ENDPOINT_NOT_ALLOWED"
    );
  }
  return url;
}

function hourBucket(nowMs) {
  return new Date(nowMs).toISOString().slice(0, 13).replace(/[-T:]/g, "");
}

function bodyBytes(value) {
  return Buffer.byteLength(String(value ?? ""), "utf8");
}

function looksBlocked(value) {
  const sample = String(value ?? "").slice(0, 200_000);
  return /(?:captcha|비정상(?:적인)?\s*(?:접근|요청)|접근이\s*차단|access\s+denied|verify\s+you(?:'re|\s+are)\s+human)/i.test(sample);
}

function retryAfterMs(value, nowMs) {
  if (value == null || value === "") return 0;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds * 1_000);
  const timestamp = Date.parse(String(value));
  return Number.isFinite(timestamp) ? Math.max(0, timestamp - nowMs) : 0;
}

export function stampPageObservation(result, page, observedAt) {
  const timestamp = new Date(observedAt).toISOString();
  return {
    ...result,
    games: result.games.map((game) => ({
      ...game,
      meta: {
        ...game.meta,
        scheduleObservedAt: timestamp,
        resultObservedAt: timestamp,
        resultSource: page === "scoreboard" ? "SCOREBOARD" : "SCHEDULE",
      },
    })),
  };
}

export function createKboPageSource(config, dependencies = {}) {
  const now = dependencies.now ?? (() => Date.now());
  if (config.provider === "fixture") {
    return {
      kind: "fixture",
      async fetchScheduleMonth(dateKey) {
        const html = await readFile(config.fixture.scheduleFile, "utf8");
        return stampPageObservation(parseKboScheduleMonthPage(html, dateKey.slice(0, 7)), "schedule", now());
      },
      async fetchSchedule(dateKey) {
        const html = await readFile(config.fixture.scheduleFile, "utf8");
        return stampPageObservation(parseKboSchedulePage(html, dateKey), "schedule", now());
      },
      async fetchScoreboard(dateKey) {
        const html = await readFile(config.fixture.scoreboardFile, "utf8");
        return stampPageObservation(parseKboScoreboardPage(html, dateKey), "scoreboard", now());
      },
      getMetrics: () => ({ logicalRequests: 0, attempts: 0 }),
    };
  }

  const sleep = dependencies.sleep ?? abortableSleep;
  const random = dependencies.random ?? Math.random;
  const state = dependencies.state;
  if (
    !state
    || typeof state.reserveQuota !== "function"
    || typeof state.getJson !== "function"
    || typeof state.putJson !== "function"
  ) {
    throw new KboRequestError(
      "KBO source requires a persistent quota and circuit state store",
      "KBO_STATE_STORE_REQUIRED"
    );
  }
  const client = dependencies.client ?? axios.create({
    timeout: config.request.timeoutMs,
    maxRedirects: 0,
    maxContentLength: config.request.maxResponseBytes,
    maxBodyLength: config.request.maxResponseBytes,
    decompress: true,
    proxy: false,
    responseType: "text",
    validateStatus: () => true,
    headers: {
      accept: "text/html,application/xhtml+xml;q=0.9",
      "accept-language": "ko-KR,ko;q=0.9,en;q=0.5",
      "user-agent": `${config.identity.product}/${config.identity.version} (+${config.identity.contact})`,
    },
  });
  const validators = new Map();
  let nextRequestAt = 0;
  let logicalRequests = 0;
  let attempts = 0;

  async function assertCircuitClosed() {
    const circuit = await state?.getJson?.("CIRCUIT#KBO");
    if (circuit?.openUntil && circuit.openUntil > now()) {
      throw new KboRequestError(
        `KBO circuit is open until ${new Date(circuit.openUntil).toISOString()}`,
        "KBO_CIRCUIT_OPEN"
      );
    }
  }

  async function tripCircuit(code, durationMs) {
    await state?.putJson?.("CIRCUIT#KBO", {
      code,
      openUntil: now() + durationMs,
      openedAt: new Date(now()).toISOString(),
    }, Math.floor((now() + durationMs) / 1000) + 86_400);
  }

  async function reserve(kind, limit, signal) {
    if (signal?.aborted) throw signal.reason ?? new DOMException("Aborted", "AbortError");
    await state?.reserveQuota?.(`KBO#${kind}#${hourBucket(now())}`, limit, Math.floor(now() / 1000) + 7_200);
  }

  async function request(capability, signal, form = null) {
    assertLivePolicy(config, new Date(now()));
    const url = capability === "scheduleData" ? KBO_ENDPOINTS.scheduleData : config.endpoints[capability];
    assertExactEndpoint(capability, url);
    await assertCircuitClosed();
    await reserve("LOGICAL", config.request.maxLogicalRequestsPerHour, signal);
    logicalRequests += 1;

    let lastError = null;
    for (let attempt = 0; attempt <= config.request.retryCount; attempt += 1) {
      if (signal?.aborted) throw signal.reason ?? new DOMException("Aborted", "AbortError");
      const waitMs = Math.max(0, nextRequestAt - now());
      if (waitMs > 0) await sleep(waitMs, signal);
      nextRequestAt = now() + config.request.minHostIntervalMs;
      await reserve("ATTEMPT", config.request.maxAttemptsPerHour, signal);
      attempts += 1;

      const cached = validators.get(url);
      const headers = {};
      if (cached?.etag) headers["if-none-match"] = cached.etag;
      if (cached?.lastModified) headers["if-modified-since"] = cached.lastModified;
      try {
        const response = form == null
          ? await client.get(url, { headers, signal })
          : await client.post(url, form.toString(), { headers: {
            ...headers,
            "content-type": "application/x-www-form-urlencoded; charset=UTF-8",
            referer: KBO_ENDPOINTS.schedule,
          }, signal });
        const status = response.status;
        if (status === 304 && cached?.body != null) return cached.body;
        if (status >= 300 && status < 400) {
          throw new KboRequestError("KBO redirect was blocked", "KBO_REDIRECT_BLOCKED", { status });
        }
        if (status === 401 || status === 403) {
          await tripCircuit("KBO_ACCESS_DENIED", 24 * 60 * 60_000);
          throw new KboRequestError(`KBO access was denied with HTTP ${status}`, "KBO_ACCESS_DENIED", { status });
        }
        if (status === 429) {
          const requestedPause = retryAfterMs(response.headers?.["retry-after"], now());
          await tripCircuit("KBO_RATE_LIMITED", Math.max(6 * 60 * 60_000, requestedPause));
          throw new KboRequestError("KBO rate limit response received; stopping without retry", "KBO_RATE_LIMITED", { status });
        }
        if (status < 200 || status >= 300) {
          if (status < 500 || attempt >= config.request.retryCount) {
            await tripCircuit("KBO_HTTP_ERROR", status < 500 ? 24 * 60 * 60_000 : 15 * 60_000);
            throw new KboRequestError(`KBO returned HTTP ${status}`, "KBO_HTTP_ERROR", { status });
          }
          throw Object.assign(new Error(`HTTP ${status}`), { retryable: true });
        }
        const body = typeof response.data === "object" ? JSON.stringify(response.data) : String(response.data ?? "");
        if (bodyBytes(body) > config.request.maxResponseBytes) {
          throw new KboRequestError("KBO response exceeded the byte limit", "KBO_RESPONSE_TOO_LARGE");
        }
        if (looksBlocked(body)) {
          await tripCircuit("KBO_BOT_CHALLENGE", 24 * 60 * 60_000);
          throw new KboRequestError("KBO bot/access challenge detected; stopping", "KBO_BOT_CHALLENGE");
        }
        if (capability === "scheduleData") {
          try {
            const data = JSON.parse(body);
            if (!Array.isArray(data?.rows)) throw new Error("rows missing");
            return data;
          } catch {
            await tripCircuit("KBO_PAGE_SCHEMA_MISMATCH", 6 * 60 * 60_000);
            throw new KboRequestError("KBO schedule endpoint did not return a rows JSON response", "KBO_PAGE_SCHEMA_MISMATCH");
          }
        }
        if (!/<(?:!doctype\s+html|html|body)\b/i.test(body)) {
          await tripCircuit("KBO_NON_HTML_RESPONSE", 6 * 60 * 60_000);
          throw new KboRequestError("KBO response was not an HTML page", "KBO_NON_HTML_RESPONSE");
        }
        if (looksBlocked(body)) {
          await tripCircuit("KBO_BOT_CHALLENGE", 24 * 60 * 60_000);
          throw new KboRequestError("KBO bot/access challenge detected; stopping", "KBO_BOT_CHALLENGE");
        }
        validators.set(url, {
          etag: response.headers?.etag ?? null,
          lastModified: response.headers?.["last-modified"] ?? null,
          body,
        });
        return body;
      } catch (error) {
        if (error instanceof KboRequestError) throw error;
        if (error?.name === "AbortError" || error?.code === "ERR_CANCELED") throw error;
        lastError = error;
        if (attempt >= config.request.retryCount) break;
        const delay = Math.floor(random() * config.request.retryBaseDelayMs * (2 ** attempt));
        await sleep(Math.max(250, delay), signal);
      }
    }
    await tripCircuit("KBO_REQUEST_FAILED", 15 * 60_000);
    throw new KboRequestError(
      `KBO ${capability} request failed after bounded retry`,
      "KBO_REQUEST_FAILED",
      { cause: String(lastError?.message ?? "unknown").slice(0, 200) }
    );
  }

  async function fetchMonth(dateKey, options = {}) {
    const ajax = config.scheduleTransport === "page-ajax";
    const result = ajax
      ? await request("scheduleData", options.signal, new URLSearchParams({
        leId: "1", srIdList: config.scheduleSeries, seasonId: dateKey.slice(0, 4),
        gameMonth: dateKey.slice(5, 7), teamId: "",
      }))
      : await request("schedule", options.signal);
    try {
      return stampPageObservation(ajax
        ? parseKboScheduleResponse(result, dateKey.slice(0, 7), config.scheduleSeries)
        : parseKboScheduleMonthPage(result, dateKey.slice(0, 7)), "schedule", now());
    } catch (error) {
      await tripCircuit("KBO_PAGE_SCHEMA_MISMATCH", 6 * 60 * 60_000);
      throw error;
    }
  }

  return {
    kind: "kbo",
    async fetchScheduleMonth(dateKey, options = {}) {
      return fetchMonth(dateKey, options);
    },
    async fetchSchedule(dateKey, options = {}) {
      const month = await fetchMonth(dateKey, options);
      const games = month.games.filter(game => game.date === dateKey);
      const anomalies = month.anomalies.filter(entry => entry.date === dateKey);
      if ((games.length === 0 && anomalies.length > 0) || games.length > 10) {
        await tripCircuit("KBO_PAGE_SCHEMA_MISMATCH", 6 * 60 * 60_000);
        throw new KboRequestError("Invalid target-date schedule rows", "KBO_PAGE_SCHEMA_MISMATCH");
      }
      return { games, anomalies, pageDate: month.pageDate };
    },
    async fetchScoreboard(dateKey, options = {}) {
      const html = await request("scoreboard", options.signal);
      try {
        return stampPageObservation(parseKboScoreboardPage(html, dateKey), "scoreboard", now());
      } catch (error) {
        await tripCircuit("KBO_PAGE_SCHEMA_MISMATCH", 6 * 60 * 60_000);
        throw error;
      }
    },
    getMetrics: () => ({ logicalRequests, attempts }),
  };
}

export { assertExactEndpoint };
