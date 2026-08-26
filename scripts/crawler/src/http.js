import axios from "axios";
import robotsParser from "robots-parser";
import {
  PolicyError,
  assertPolicyAllowed,
  assertProviderCapability,
  selectedProviderNames,
} from "./policy.js";

const RETRYABLE_STATUS = new Set([408, 425, 500, 502, 503, 504]);
const NO_RETRY_STATUS = new Set([400, 401, 403, 404, 405, 409, 410, 422]);
const RETRYABLE_CODES = new Set([
  "ECONNABORTED",
  "ECONNRESET",
  "EAI_AGAIN",
  "ENETDOWN",
  "ENETUNREACH",
  "ETIMEDOUT",
]);

export class HttpPolicyError extends Error {
  constructor(message, code = "HTTP_POLICY_ERROR", cause = null) {
    super(message, cause ? { cause } : undefined);
    this.name = "HttpPolicyError";
    this.code = code;
  }
}

export function parseRetryAfter(value, nowMs = Date.now()) {
  if (value == null || value === "") return null;
  const seconds = Number(value);
  if (Number.isFinite(seconds) && seconds >= 0) return Math.ceil(seconds * 1000);
  const when = Date.parse(String(value));
  if (Number.isNaN(when)) return null;
  return Math.max(0, when - nowMs);
}

export function isRetryableError(error) {
  const status = error?.response?.status;
  if (NO_RETRY_STATUS.has(status)) return false;
  if (RETRYABLE_STATUS.has(status)) return true;
  if (status != null) return false;
  return RETRYABLE_CODES.has(error?.code) || error?.name === "AbortError";
}

export function sanitizeUrl(rawUrl) {
  try {
    const url = new URL(rawUrl);
    url.username = "";
    url.password = "";
    url.search = "";
    url.hash = "";
    return url.toString();
  } catch {
    return "<invalid-url>";
  }
}

function sleepDefault(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

function buildUserAgent(config) {
  const product = config.identity?.product || "InningLogCrawler";
  const version = config.identity?.version || "2.0";
  const contact = String(config.identity?.contact ?? "").trim();
  return `${product}/${version}${contact ? ` (+${contact})` : ""}`;
}

export function buildAxiosOptions(config) {
  return {
    timeout: config.request?.timeoutMs ?? 10_000,
    maxRedirects: 0,
    maxContentLength: config.request?.maxResponseBytes ?? 2_000_000,
    maxBodyLength: config.request?.maxResponseBytes ?? 2_000_000,
    decompress: true,
    proxy: false,
    headers: {
      ...config.request?.headers,
      "user-agent": buildUserAgent(config),
    },
    validateStatus: () => true,
  };
}

function normalizeAllowedHosts(provider) {
  const hosts = provider?.allowedHosts ?? [];
  return new Set(hosts.map((host) => String(host).toLowerCase()));
}

function assertSafeUrl(config, providerName, rawUrl) {
  let url;
  try {
    url = new URL(rawUrl);
  } catch (error) {
    throw new HttpPolicyError("유효하지 않은 요청 URL입니다.", "INVALID_URL", error);
  }
  if (url.username || url.password) {
    throw new HttpPolicyError(
      "URL 사용자정보(user:password)는 허용하지 않습니다.",
      "URL_CREDENTIALS_BLOCKED"
    );
  }
  if (config.policy?.requireHttps !== false && url.protocol !== "https:") {
    throw new HttpPolicyError("HTTPS가 아닌 요청은 차단됩니다.", "HTTPS_REQUIRED");
  }
  const provider = config.providers?.[providerName];
  const allowedHosts = normalizeAllowedHosts(provider);
  if (!allowedHosts.has(url.hostname.toLowerCase())) {
    throw new HttpPolicyError(
      `${url.hostname}은 ${providerName} host allowlist에 없습니다.`,
      "HOST_NOT_ALLOWED"
    );
  }
  return url;
}

function responseBodySize(data) {
  if (typeof data === "string") return Buffer.byteLength(data, "utf8");
  if (Buffer.isBuffer(data)) return data.byteLength;
  return Buffer.byteLength(JSON.stringify(data ?? null), "utf8");
}

function looksLikeBotChallenge(data) {
  if (typeof data !== "string") return false;
  const sample = data.slice(0, 200_000);
  return /(?:captcha|비정상(?:적인)?\s*(?:접근|요청)|접근이\s*차단|access\s+denied|verify\s+you(?:'re|\s+are)\s+human)/i.test(
    sample
  );
}

export function createHttpCollector(config, dependencies = {}) {
  const sleep = dependencies.sleep ?? sleepDefault;
  const random = dependencies.random ?? Math.random;
  const now = dependencies.now ?? (() => Date.now());
  const client =
    dependencies.client ??
    axios.create(buildAxiosOptions(config));

  const hostNextAt = new Map();
  const hostQueues = new Map();
  const circuit = new Map();
  const validators = new Map();
  const robotsCache = new Map();
  const robotsRefreshes = new Map();
  const inFlight = new Map();
  let requestAttempts = 0;
  let logicalRequests = 0;

  const minIntervalMs =
    config.request?.minHostIntervalMs ?? 2_000;
  const maxLogicalRequests = config.request?.maxRequestsPerRun ?? 12;
  const maxAttempts = config.request?.maxAttemptsPerRun ?? 24;
  const retryCount =
    config.request?.retry?.count ?? 2;
  const baseDelayMs =
    config.request?.retry?.baseDelayMs ?? 800;
  const maxDelayMs = config.request?.retry?.maxDelayMs ?? 30_000;
  const maxResponseBytes = config.request?.maxResponseBytes ?? 2_000_000;
  const memoryCacheTtlMs = config.request?.memoryCacheTtlMs ?? 60 * 60 * 1000;
  const failureThreshold = config.request?.circuitBreaker?.failureThreshold ?? 3;
  const cooldownMs = config.request?.circuitBreaker?.cooldownMs ?? 6 * 60 * 60 * 1000;

  function assertCircuit(providerName) {
    const state = circuit.get(providerName);
    if (state?.openUntil && state.openUntil > now()) {
      throw new HttpPolicyError(
        `${providerName} circuit가 ${new Date(state.openUntil).toISOString()}까지 열려 있습니다.`,
        "CIRCUIT_OPEN"
      );
    }
  }

  function recordFailure(providerName, forceOpen = false) {
    const previous = circuit.get(providerName) ?? { failures: 0, openUntil: 0 };
    const failures = previous.failures + 1;
    circuit.set(providerName, {
      failures,
      openUntil:
        forceOpen || failures >= failureThreshold ? now() + cooldownMs : 0,
    });
  }

  function recordSuccess(providerName) {
    circuit.set(providerName, { failures: 0, openUntil: 0 });
  }

  async function waitForHost(hostname) {
    const readyAt = hostNextAt.get(hostname) ?? 0;
    const waitMs = Math.max(0, readyAt - now());
    if (waitMs > 0) await sleep(waitMs);
    const jitterRatio = config.request?.jitterRatio ?? 0.15;
    const jitter = Math.floor(minIntervalMs * jitterRatio * random());
    hostNextAt.set(hostname, now() + minIntervalMs + jitter);
  }

  async function withHostRequest(hostname, operation) {
    const previous = hostQueues.get(hostname) ?? Promise.resolve();
    let release;
    const current = new Promise((resolve) => {
      release = resolve;
    });
    hostQueues.set(hostname, current);
    await previous;
    try {
      await waitForHost(hostname);
      return await operation();
    } finally {
      release();
      if (hostQueues.get(hostname) === current) hostQueues.delete(hostname);
    }
  }

  async function rawGet(providerName, url, options = {}) {
    const parsedUrl = assertSafeUrl(config, providerName, url);
    assertCircuit(providerName);

    for (const [cachedUrl, entry] of validators) {
      if (now() - entry.cachedAt >= memoryCacheTtlMs) {
        validators.delete(cachedUrl);
      }
    }
    let cached = validators.get(url);
    const headers = { ...(options.headers ?? {}) };
    if (options.conditional !== false && cached?.etag) {
      headers["if-none-match"] = cached.etag;
    }
    if (options.conditional !== false && cached?.lastModified) {
      headers["if-modified-since"] = cached.lastModified;
    }

    let lastError;
    for (let attempt = 0; attempt <= retryCount; attempt += 1) {
      try {
        const response = await withHostRequest(parsedUrl.hostname, async () => {
          if (requestAttempts >= maxAttempts) {
            throw new HttpPolicyError(
              `실행당 HTTP attempt 상한(${maxAttempts})에 도달했습니다.`,
              "ATTEMPT_BUDGET_EXHAUSTED"
            );
          }
          requestAttempts += 1;
          return client.get(url, {
            headers,
            responseType: options.responseType ?? "text",
          });
        });
        const status = response.status;

        if (status === 304 && cached?.body != null) {
          recordSuccess(providerName);
          return { ...response, status, data: cached.body, notModified: true };
        }
        if (options.acceptStatuses?.includes(status)) {
          recordSuccess(providerName);
          return response;
        }
        if (status >= 300 && status < 400) {
          recordFailure(providerName, true);
          throw new HttpPolicyError(
            `자동 redirect를 차단했습니다: ${sanitizeUrl(url)}`,
            "REDIRECT_BLOCKED"
          );
        }
        if (status === 429) {
          const retryAfterMs = parseRetryAfter(
            response.headers?.["retry-after"],
            now()
          );
          const pauseMs = Math.max(retryAfterMs ?? 0, cooldownMs);
          circuit.set(providerName, {
            failures: failureThreshold,
            openUntil: now() + pauseMs,
          });
          throw new HttpPolicyError(
            `${providerName}가 요청률을 제한했습니다. 최소 ${pauseMs}ms 동안 신규 요청을 중단합니다.`,
            "RATE_LIMITED"
          );
        }
        if (status === 401 || status === 403) {
          recordFailure(providerName, true);
          throw new HttpPolicyError(
            `${providerName}가 HTTP ${status}로 접근을 거부했습니다. 재시도하지 않습니다.`,
            "ACCESS_DENIED"
          );
        }
        if (NO_RETRY_STATUS.has(status)) {
          recordFailure(providerName, true);
          throw new HttpPolicyError(
            `${providerName}가 HTTP ${status}로 요청을 거부했습니다. provider 회로를 열고 재시도하지 않습니다.`,
            status === 404 || status === 410
              ? "ENDPOINT_NOT_FOUND"
              : "REQUEST_REJECTED"
          );
        }
        if (status < 200 || status >= 300) {
          const error = new Error(`HTTP ${status}`);
          error.response = response;
          throw error;
        }
        if (responseBodySize(response.data) > maxResponseBytes) {
          recordFailure(providerName, true);
          throw new HttpPolicyError(
            `응답 크기가 상한(${maxResponseBytes} bytes)을 넘었습니다.`,
            "RESPONSE_TOO_LARGE"
          );
        }
        if (looksLikeBotChallenge(response.data)) {
          recordFailure(providerName, true);
          throw new HttpPolicyError(
            `${providerName} 응답에서 자동 접근 차단 신호를 발견했습니다. 우회하지 않고 provider 회로를 엽니다.`,
            "BOT_CHALLENGE"
          );
        }

        validators.set(url, {
          etag: response.headers?.etag ?? null,
          lastModified: response.headers?.["last-modified"] ?? null,
          body: response.data,
          cachedAt: now(),
        });
        recordSuccess(providerName);
        return response;
      } catch (error) {
        lastError = error;
        if (error instanceof HttpPolicyError) throw error;
        if (!isRetryableError(error) || attempt >= retryCount) {
          recordFailure(providerName);
          throw new HttpPolicyError(
            `${providerName} 요청 실패: ${sanitizeUrl(url)} (${error?.message ?? "unknown"})`,
            "REQUEST_FAILED",
            error
          );
        }

        const retryAfterMs = parseRetryAfter(
          error?.response?.headers?.["retry-after"],
          now()
        );
        if (retryAfterMs != null && retryAfterMs > maxDelayMs) {
          circuit.set(providerName, {
            failures: failureThreshold,
            openUntil: now() + retryAfterMs,
          });
          throw new HttpPolicyError(
            `${providerName} Retry-After가 ${retryAfterMs}ms입니다. 더 일찍 재요청하지 않고 circuit를 열었습니다.`,
            "RATE_LIMITED"
          );
        }
        const exponential = Math.min(maxDelayMs, baseDelayMs * 2 ** attempt);
        const delayMs =
          retryAfterMs ?? Math.floor(random() * exponential);
        await sleep(delayMs);
      }
    }
    throw lastError;
  }

  async function ensureRobotsAllowed(providerName, targetUrl) {
    if (config.policy?.respectRobotsTxt === false) return;
    const provider = config.providers?.[providerName];
    if (!provider?.robotsUrl) {
      throw new PolicyError(`${providerName} robotsUrl이 없어 fail-closed 처리했습니다.`);
    }

    const ttlMs = config.policy?.robotsCacheTtlMs ?? 24 * 60 * 60 * 1000;
    let entry = robotsCache.get(providerName);
    if (!entry || now() - entry.fetchedAt >= ttlMs) {
      let refresh = robotsRefreshes.get(providerName);
      if (!refresh) {
        refresh = (async () => {
          const response = await rawGet(providerName, provider.robotsUrl, {
            conditional: true,
            acceptStatuses: [404, 410],
          });
          if (response.status === 404 || response.status === 410) {
            return { fetchedAt: now(), rules: null, missing: true };
          }
          const body = String(response.data ?? "");
          return {
            fetchedAt: now(),
            rules: robotsParser(provider.robotsUrl, body),
            missing: false,
          };
        })();
        robotsRefreshes.set(providerName, refresh);
      }
      try {
        entry = await refresh;
        robotsCache.set(providerName, entry);
      } finally {
        if (robotsRefreshes.get(providerName) === refresh) {
          robotsRefreshes.delete(providerName);
        }
      }
    }

    if (entry.missing && provider.authorization?.robotsExceptionGranted !== true) {
      throw new PolicyError(
        `${providerName} robots.txt를 확인할 수 없어 strict fail-closed 처리했습니다.`
      );
    }
    if (!entry.missing) {
      const userAgent = config.identity?.product || "InningLogCrawler";
      const allowed = entry.rules?.isAllowed(targetUrl, userAgent);
      if (allowed !== true && provider.authorization?.robotsExceptionGranted !== true) {
        throw new PolicyError(
          `${providerName} robots.txt가 ${sanitizeUrl(targetUrl)} 접근을 허용하지 않습니다.`
        );
      }
    }
  }

  async function executeTextRequest(providerName, capability, url) {
    if (!selectedProviderNames(config.source).includes(providerName)) {
      throw new PolicyError(
        `${providerName}는 현재 source=${config.source ?? "undefined"}에 포함된 provider가 아닙니다.`
      );
    }
    assertPolicyAllowed(config, { now: new Date(now()) });
    assertProviderCapability(config, providerName, capability);
    if (logicalRequests >= maxLogicalRequests) {
      throw new HttpPolicyError(
        `실행당 요청 상한(${maxLogicalRequests})에 도달했습니다.`,
        "REQUEST_BUDGET_EXHAUSTED"
      );
    }
    logicalRequests += 1;
    assertSafeUrl(config, providerName, url);
    await ensureRobotsAllowed(providerName, url);
    const response = await rawGet(providerName, url);
    return String(response.data ?? "");
  }

  async function requestText(providerName, capability, url) {
    const key = `${providerName}\u0000${capability}\u0000${url}`;
    const existing = inFlight.get(key);
    if (existing) return existing;

    const operation = executeTextRequest(providerName, capability, url);
    inFlight.set(key, operation);
    try {
      return await operation;
    } finally {
      if (inFlight.get(key) === operation) inFlight.delete(key);
    }
  }

  async function requestJson(providerName, capability, url) {
    const text = await requestText(providerName, capability, url);
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new HttpPolicyError(
        `${providerName} 응답이 유효한 JSON이 아닙니다.`,
        "INVALID_JSON_RESPONSE",
        error
      );
    }
  }

  return {
    requestText,
    requestJson,
    getMetrics: () => ({
      logicalRequests,
      requestAttempts,
      circuits: Object.fromEntries(circuit),
    }),
    resetRunBudget: () => {
      logicalRequests = 0;
      requestAttempts = 0;
    },
    tripCircuit: (providerName, durationMs = cooldownMs) => {
      circuit.set(providerName, {
        failures: failureThreshold,
        openUntil: now() + Math.max(durationMs, 60_000),
      });
    },
    getConditionalRequestState: () =>
      Object.fromEntries(
        [...validators.entries()].map(([url, value]) => [url, {
          etag: value.etag,
          lastModified: value.lastModified,
        }])
      ),
  };
}
