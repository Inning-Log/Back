import test from "node:test";
import assert from "node:assert/strict";
import {
  buildAxiosOptions,
  createHttpCollector,
  isRetryableError,
  parseRetryAfter,
  sanitizeUrl,
} from "../src/http.js";

const PROVIDER = "naver";
const HOST = "sports.example.test";
const ROBOTS_URL = `https://${HOST}/robots.txt`;
const GAME_URL = `https://${HOST}/games/20260826`;
const ROBOTS_ALLOW_ALL = "User-agent: *\nAllow: /\n";

function collectorConfig(request = {}) {
  return {
    source: PROVIDER,
    identity: {
      product: "InningLogCrawlerTest",
      version: "1.0.0",
      contact: "mailto:test@example.test",
    },
    policy: {
      enforcement: "strict",
      killSwitch: false,
      requireHttps: true,
      respectRobotsTxt: true,
      robotsCacheTtlMs: 60_000,
      authorizationReviewMaxAgeMs: 7 * 24 * 60 * 60 * 1000,
      allowUndocumentedApi: true,
    },
    run: {},
    request: {
      minHostIntervalMs: 2_000,
      maxRequestsPerRun: 10,
      maxAttemptsPerRun: 20,
      maxResponseBytes: 1_000_000,
      memoryCacheTtlMs: 60_000,
      jitterRatio: 0.1,
      retry: {
        count: 3,
        baseDelayMs: 100,
        maxDelayMs: 1_000,
      },
      circuitBreaker: {
        failureThreshold: 3,
        cooldownMs: 60_000,
      },
      ...request,
    },
    providers: {
      [PROVIDER]: {
        enabled: true,
        documentedApi: false,
        allowedHosts: [HOST],
        robotsUrl: ROBOTS_URL,
        authorization: {
          status: "approved",
          evidence: "synthetic-test-permission",
          reviewedAt: "2026-08-25T00:00:00Z",
          expiresAt: "2026-09-25T00:00:00Z",
          scopes: ["schedule", "robots-disallow-override"],
          robotsExceptionGranted: true,
          undocumentedApiApproved: true,
        },
      },
    },
  };
}

function fakeClient(handler) {
  const calls = [];
  return {
    calls,
    async get(url, options) {
      calls.push({ url, options });
      return handler(url, options, calls.length);
    },
  };
}

function deterministicDependencies(client) {
  let clock = Date.parse("2026-08-26T00:00:00Z");
  return {
    client,
    random: () => 0,
    now: () => clock,
    sleep: async (milliseconds) => {
      clock += milliseconds;
    },
    advance: (milliseconds) => {
      clock += milliseconds;
    },
  };
}

function successfulResponse(data, headers = {}) {
  return { status: 200, data, headers };
}

test("재시도는 일부 5xx와 일시적 네트워크 오류에만 적용한다", () => {
  assert.equal(isRetryableError({ response: { status: 429 } }), false);
  assert.equal(isRetryableError({ response: { status: 503 } }), true);
  assert.equal(isRetryableError({ response: { status: 403 } }), false);
  assert.equal(isRetryableError({ response: { status: 404 } }), false);
  assert.equal(isRetryableError({ code: "ECONNRESET" }), true);
});

test("429는 같은 요청도 재시도하지 않고 Retry-After 이상 circuit를 연다", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return { status: 429, data: "slow down", headers: { "retry-after": "5" } };
  });
  const collector = createHttpCollector(
    collectorConfig(),
    deterministicDependencies(client)
  );

  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", GAME_URL),
    (error) => error?.code === "RATE_LIMITED"
  );
  assert.equal(
    client.calls.filter((call) => call.url === GAME_URL).length,
    1
  );
  assert.ok(collector.getMetrics().circuits[PROVIDER].openUntil > 0);
});

test("200 응답이어도 CAPTCHA/차단 화면이면 즉시 중단한다", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return successfulResponse("<html><title>Access denied</title><p>CAPTCHA</p></html>");
  });
  const collector = createHttpCollector(
    collectorConfig(),
    deterministicDependencies(client)
  );

  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", GAME_URL),
    (error) => error?.code === "BOT_CHALLENGE"
  );
  assert.equal(
    client.calls.filter((call) => call.url === GAME_URL).length,
    1
  );
});

test("Retry-After 초와 HTTP-date를 해석한다", () => {
  assert.equal(parseRetryAfter("3", 0), 3000);
  assert.equal(
    parseRetryAfter("Tue, 25 Aug 2026 00:00:10 GMT", Date.parse("2026-08-25T00:00:00Z")),
    10_000
  );
  assert.equal(parseRetryAfter("invalid", 0), null);
});

test("오류용 URL은 query와 credential을 노출하지 않는다", () => {
  assert.equal(
    sanitizeUrl("https://user:secret@example.com/path?token=abc#x"),
    "https://example.com/path"
  );
});

test("HTTP transport 설정은 안전 헤더, 식별 UA, redirect/proxy 차단에 연결된다", () => {
  const config = collectorConfig({
    timeoutMs: 15_000,
    maxResponseBytes: 123_456,
    headers: { accept: "application/json", "accept-language": "ko-KR" },
  });
  const options = buildAxiosOptions(config);
  assert.equal(options.timeout, 15_000);
  assert.equal(options.maxRedirects, 0);
  assert.equal(options.proxy, false);
  assert.equal(options.maxContentLength, 123_456);
  assert.equal(options.headers.accept, "application/json");
  assert.equal(options.headers["accept-language"], "ko-KR");
  assert.equal(
    options.headers["user-agent"],
    "InningLogCrawlerTest/1.0.0 (+mailto:test@example.test)"
  );
});

test("동일 provider/capability/URL 동시 호출은 하나의 HTTP 요청으로 합쳐진다", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return successfulResponse("same-body", { etag: '"v1"' });
  });
  const collector = createHttpCollector(
    collectorConfig(),
    deterministicDependencies(client)
  );

  const [left, right] = await Promise.all([
    collector.requestText(PROVIDER, "schedule", GAME_URL),
    collector.requestText(PROVIDER, "schedule", GAME_URL),
  ]);

  assert.equal(left, "same-body");
  assert.equal(right, "same-body");
  assert.equal(
    client.calls.filter((call) => call.url === ROBOTS_URL).length,
    1,
    "동시 호출은 robots.txt 조회도 공유해야 한다"
  );
  assert.equal(
    client.calls.filter((call) => call.url === GAME_URL).length,
    1,
    "동일 target HTTP 요청은 한 번만 실행되어야 한다"
  );
});

test("expired conditional bodies are swept even when their URL is never requested again", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return successfulResponse("body", { etag: `"${url}"` });
  });
  const dependencies = deterministicDependencies(client);
  const collector = createHttpCollector(
    collectorConfig({ memoryCacheTtlMs: 1_000 }),
    dependencies
  );

  await collector.requestText(PROVIDER, "schedule", GAME_URL);
  assert.ok(Object.hasOwn(collector.getConditionalRequestState(), GAME_URL));
  dependencies.advance(1_001);
  const secondUrl = `${GAME_URL}?second=1`;
  await collector.requestText(PROVIDER, "schedule", secondUrl);
  const state = collector.getConditionalRequestState();
  assert.equal(Object.hasOwn(state, GAME_URL), false);
  assert.equal(Object.hasOwn(state, secondUrl), true);
});

test("403은 재시도하지 않고 provider circuit를 즉시 연다", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return { status: 403, data: "forbidden", headers: {} };
  });
  const collector = createHttpCollector(
    collectorConfig(),
    deterministicDependencies(client)
  );

  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", GAME_URL),
    (error) => error?.code === "ACCESS_DENIED"
  );
  assert.equal(
    client.calls.filter((call) => call.url === GAME_URL).length,
    1,
    "403 target은 retryCount와 무관하게 한 번만 호출되어야 한다"
  );

  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", `${GAME_URL}?second=1`),
    (error) => error?.code === "CIRCUIT_OPEN"
  );
  assert.equal(
    client.calls.filter((call) => call.url !== ROBOTS_URL).length,
    1,
    "열린 circuit는 후속 HTTP 요청 전에 차단해야 한다"
  );
  assert.ok(collector.getMetrics().circuits[PROVIDER].openUntil > 0);
});

test("실행당 logical request 예산을 초과하면 네트워크 전에 차단한다", async () => {
  const client = fakeClient((url) =>
    url === ROBOTS_URL
      ? successfulResponse(ROBOTS_ALLOW_ALL)
      : successfulResponse("ok")
  );
  const collector = createHttpCollector(
    collectorConfig({ maxRequestsPerRun: 1 }),
    deterministicDependencies(client)
  );

  assert.equal(
    await collector.requestText(PROVIDER, "schedule", GAME_URL),
    "ok"
  );
  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", `${GAME_URL}?second=1`),
    (error) => error?.code === "REQUEST_BUDGET_EXHAUSTED"
  );
  assert.equal(collector.getMetrics().logicalRequests, 1);
  assert.equal(
    client.calls.filter((call) => call.url !== ROBOTS_URL).length,
    1
  );
});

test("robots 조회와 retry를 포함한 HTTP attempt 예산을 강제한다", async () => {
  const client = fakeClient((url) => {
    if (url === ROBOTS_URL) return successfulResponse(ROBOTS_ALLOW_ALL);
    return { status: 503, data: "unavailable", headers: {} };
  });
  const collector = createHttpCollector(
    collectorConfig({ maxAttemptsPerRun: 3 }),
    deterministicDependencies(client)
  );

  await assert.rejects(
    collector.requestText(PROVIDER, "schedule", GAME_URL),
    (error) => error?.code === "ATTEMPT_BUDGET_EXHAUSTED"
  );
  assert.equal(
    client.calls.filter((call) => call.url === ROBOTS_URL).length,
    1
  );
  assert.equal(
    client.calls.filter((call) => call.url === GAME_URL).length,
    2,
    "robots 1회와 target 2회로 attempt 3개를 모두 사용해야 한다"
  );
  assert.equal(collector.getMetrics().requestAttempts, 3);
});
