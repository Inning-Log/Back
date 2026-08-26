import assert from "node:assert/strict";
import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import {
  ConfigError,
  PROJECT_ROOT,
  deepMerge,
  evaluateCrawlerPolicy,
  formatDuration,
  formatHelp,
  isIanaTimezone,
  isStrictDate,
  loadCrawlerConfig,
  parseCliArgs,
  parseDuration,
  parseEnvFile,
  redactSecrets,
  validateCrawlerConfig,
  validateFiveFieldCron,
  validateUrlTemplate,
} from "../src/config.js";

test("default fixture profile is local-only and resolves paths from project root", async () => {
  const result = await loadCrawlerConfig({ argv: [], env: {}, warn: () => {} });
  assert.equal(result.profile, "fixture");
  assert.equal(result.config.enabled, true);
  assert.equal(result.config.source, "fixture");
  assert.equal(result.config.providers.fixture.enabled, true);
  assert.equal(result.config.providers.kbo.enabled, false);
  assert.equal(result.config.providers.kbo.authorization.status, "unverified");
  assert.equal(result.config.providers.naver.enabled, false);
  assert.equal(result.config.providers.naver.authorization.status, "unverified");
  assert.equal(result.config.output.directory, path.join(PROJECT_ROOT, "out"));
  assert.equal(
    result.config.providers.fixture.file,
    path.join(PROJECT_ROOT, "fixtures", "games.sample.json")
  );
  assert.equal(evaluateCrawlerPolicy(result.config).ok, true);

  const stoppedExternalOnly = structuredClone(result.config);
  stoppedExternalOnly.policy.killSwitch = true;
  assert.equal(
    evaluateCrawlerPolicy(stoppedExternalOnly).ok,
    true,
    "external kill switch must not disable the local fixture source"
  );
});

test("hybrid-locked profile exposes the intended topology but remains blocked", async () => {
  const result = await loadCrawlerConfig({
    argv: ["--profile", "hybrid-locked"],
    env: {},
    warn: () => {},
  });
  assert.equal(result.config.source, "hybrid");
  assert.equal(result.config.enabled, false);
  assert.equal(result.config.policy.killSwitch, true);
  assert.equal(evaluateCrawlerPolicy(result.config).ok, false);
});

test("precedence is base < profile < env file < process env < CLI and --set", async () => {
  const temporaryRoot = await mkdtemp(path.join(os.tmpdir(), "crawler-config-"));
  await mkdir(path.join(temporaryRoot, "config"), { recursive: true });
  const original = await import("node:fs/promises").then(({ readFile }) =>
    readFile(path.join(PROJECT_ROOT, "config", "crawler.yml"), "utf8")
  );
  await writeFile(path.join(temporaryRoot, "config", "crawler.yml"), original);
  await writeFile(
    path.join(temporaryRoot, "local.env"),
    "CRAWLER_MAX_GAMES=12\nCRAWLER_MAX_DETAILS=2\n"
  );

  const result = await loadCrawlerConfig({
    projectRoot: temporaryRoot,
    argv: [
      "--env-file",
      "local.env",
      "--max-games",
      "18",
      "--set",
      "request.timeoutMs=15s",
    ],
    env: { CRAWLER_MAX_DETAILS: "3" },
    warn: () => {},
  });
  assert.equal(result.config.run.maxGamesPerRun, 18);
  assert.equal(result.config.run.maxDetailGamesPerRun, 3);
  assert.equal(result.config.request.timeoutMs, 15_000);
  assert.deepEqual(result.precedence, [
    "yaml.base",
    "yaml.profiles.fixture",
    "environment",
    "cli",
  ]);
});

test("CLI parser rejects unknown, missing, duplicate and conflicting arguments", () => {
  const cases = [
    ["--wat"],
    ["positional"],
    ["--source"],
    ["--source=kbo", "--source=naver"],
    ["--source=kbo", "--set", "source=naver"],
    ["--once", "--scheduled"],
    ["--cron", "--scheduled"],
    ["--force-write", "--no-write"],
    ["--dry-run", "--no-write"],
    ["--set", "run.maxGamesPerRun=10", "--set", "run.maxGamesPerRun=11"],
  ];
  for (const argv of cases) {
    assert.throws(() => parseCliArgs(argv), ConfigError, argv.join(" "));
  }
});

test("CLI parser accepts all supported action and scheduled forms", () => {
  const parsed = parseCliArgs([
    "--config=config/crawler.yml",
    "--profile=fixture",
    "--env-file=.env",
    "--source=fixture",
    "--date=2026-08-25",
    "--out=out/test",
    "--scheduled=*/10 * * * *",
    "--max-games=10",
    "--max-details=2",
    "--no-write",
    "--print-config",
    "--check-config",
    "--check-policy",
    "--help",
  ]);
  assert.equal(parsed.configPath, "config/crawler.yml");
  assert.equal(parsed.profile, "fixture");
  assert.equal(parsed.overrides.run.mode, "scheduled");
  assert.equal(parsed.overrides.run.cron, "*/10 * * * *");
  assert.equal(parsed.overrides.output.enabled, false);
  assert.equal(parsed.actions.checkPolicy, true);
});

test("strict dates, IANA timezones, cron and human durations are validated", () => {
  assert.equal(isStrictDate("2024-02-29"), true);
  assert.equal(isStrictDate("2023-02-29"), false);
  assert.equal(isStrictDate("2026-8-25"), false);
  assert.equal(isIanaTimezone("Asia/Seoul"), true);
  assert.equal(isIanaTimezone("+09:00"), false);
  assert.equal(validateFiveFieldCron("*/5 * * * *").ok, true);
  assert.equal(validateFiveFieldCron("*/5 * * * * *").ok, false);
  assert.equal(validateFiveFieldCron("*/0 * * * *").ok, false);
  assert.equal(parseDuration("2m 30s"), 150_000);
  assert.equal(parseDuration("800ms"), 800);
  assert.equal(formatDuration(150_800), "2m 30s 800ms");
  assert.throws(() => parseDuration("soon"));
});

test("URL templates require HTTPS, an allowlisted host, and placeholders", () => {
  const valid = validateUrlTemplate(
    "https://example.com/games?date={dateYYYYMMDD}",
    { allowedHosts: ["example.com"], requiredPlaceholders: ["dateYYYYMMDD"] }
  );
  assert.equal(valid.ok, true);
  assert.equal(
    validateUrlTemplate("http://example.com/games", {
      allowedHosts: ["example.com"],
      requiredPlaceholders: ["dateYYYYMMDD"],
    }).ok,
    false
  );
  assert.equal(
    validateUrlTemplate("https://evil.example/games/{unknown}", {
      allowedHosts: ["example.com"],
    }).ok,
    false
  );
  assert.equal(
    validateUrlTemplate("https://example.com/games?api_key=secret", {
      allowedHosts: ["example.com"],
    }).ok,
    false
  );
});

test("legacy SCRAPER variables work with warnings and CRAWLER variables win", async () => {
  const warnings = [];
  const result = await loadCrawlerConfig({
    argv: [],
    env: {
      SCRAPER_MAX_GAMES: "11",
      SCRAPER_MAX_DETAILS: "3",
      CRAWLER_MAX_GAMES: "12",
    },
    warn: (message) => warnings.push(message),
  });
  assert.equal(result.config.run.maxGamesPerRun, 12);
  assert.equal(result.config.run.maxDetailGamesPerRun, 3);
  assert.equal(warnings.length, 2);
  assert.match(warnings.join("\n"), /deprecated/);
});

test("environment supports scoped authorization lists and makes dry-run non-writing", async () => {
  const result = await loadCrawlerConfig({
    argv: [],
    env: {
      CRAWLER_DRY_RUN: "true",
      CRAWLER_KBO_AUTHORIZATION_SCOPES:
        "schedule,robots-disallow-override",
    },
    warn: () => {},
  });
  assert.equal(result.config.output.dryRun, true);
  assert.equal(result.config.output.enabled, false);
  assert.deepEqual(result.config.providers.kbo.authorization.scopes, [
    "schedule",
    "robots-disallow-override",
  ]);
});

test("operational budgets are bounded and external approval requires expiry", async () => {
  await assert.rejects(
    loadCrawlerConfig({ argv: ["--max-games=21"], env: {}, warn: () => {} }),
    ConfigError
  );
  await assert.rejects(
    loadCrawlerConfig({
      argv: ["--set", "request.minHostIntervalMs=1999ms"],
      env: {},
      warn: () => {},
    }),
    ConfigError
  );
  await assert.rejects(
    loadCrawlerConfig({
      argv: ["--set", "policy.robotsCacheTtlMs=25h"],
      env: {},
      warn: () => {},
    }),
    ConfigError
  );
  await assert.rejects(
    loadCrawlerConfig({
      argv: [
        "--set",
        'strategy.polling.finishedOffsetsMs=["30m","5m"]',
      ],
      env: {},
      warn: () => {},
    }),
    ConfigError
  );

  const loaded = await loadCrawlerConfig({ argv: [], env: {}, warn: () => {} });
  const candidate = structuredClone(loaded.config);
  candidate.providers.kbo.authorization = {
    status: "approved",
    evidence: "written-permission-reference",
    reviewedAt: "2026-08-25T00:00:00+09:00",
    expiresAt: null,
    scopes: ["schedule", "robots-disallow-override"],
    robotsExceptionGranted: true,
    undocumentedApiApproved: true,
  };
  assert.throws(() => validateCrawlerConfig(candidate), ConfigError);
});

test("KBO known robots block needs an explicit written override scope", async () => {
  const loaded = await loadCrawlerConfig({ argv: [], env: {}, warn: () => {} });
  const candidate = structuredClone(loaded.config);
  candidate.source = "kbo";
  candidate.providers.kbo.enabled = true;
  candidate.providers.kbo.authorization = {
    status: "approved",
    evidence: "written-permission-reference",
    reviewedAt: "2026-08-25T00:00:00+09:00",
    expiresAt: "2026-09-25T00:00:00+09:00",
    scopes: ["schedule"],
    robotsExceptionGranted: false,
    undocumentedApiApproved: true,
  };
  candidate.policy.allowUndocumentedApi = true;
  const validConfig = validateCrawlerConfig(candidate);
  const policy = evaluateCrawlerPolicy(validConfig, {
    now: new Date("2026-08-26T00:00:00+09:00"),
  });
  assert.equal(policy.ok, false);
  assert.ok(
    policy.errors.some((entry) => entry.code === "KBO_ROBOTS_EXCEPTION_REQUIRED")
  );
  assert.ok(
    policy.errors.some((entry) => entry.code === "BOT_CONTACT_PLACEHOLDER")
  );
});

test("unknown CRAWLER variables and duplicate env-file keys are rejected", async () => {
  await assert.rejects(
    loadCrawlerConfig({ argv: [], env: { CRAWLER_MAX_GAMEZ: "10" } }),
    ConfigError
  );
  assert.throws(
    () => parseEnvFile("CRAWLER_SOURCE=fixture\nCRAWLER_SOURCE=kbo\n"),
    ConfigError
  );
});

test("secret redaction keeps policy structure while hiding evidence and credentials", () => {
  const redacted = redactSecrets({
    headers: { authorization: "Bearer abc", cookie: "session=abc" },
    authorization: { status: "approved", evidence: "contract-123" },
    endpoint: "https://example.com/?token=abc&date=1",
  });
  assert.equal(redacted.headers.authorization, "[REDACTED]");
  assert.equal(redacted.headers.cookie, "[REDACTED]");
  assert.equal(redacted.authorization.status, "approved");
  assert.equal(redacted.authorization.evidence, "[REDACTED]");
  assert.doesNotMatch(redacted.endpoint, /token=abc/);
});

test("deep merge replaces arrays and blocks prototype-pollution paths", () => {
  assert.deepEqual(
    deepMerge({ a: { b: 1, list: [1, 2] } }, { a: { list: [3] } }),
    { a: { b: 1, list: [3] } }
  );
  assert.throws(() => parseCliArgs(["--set", "__proto__.polluted=true"]), ConfigError);
});

test("help documents safety, priority and all operational modes", () => {
  const help = formatHelp();
  assert.match(help, /YAML base < selected YAML profile/);
  assert.match(help, /--check-policy/);
  assert.match(help, /--force-write/);
  assert.match(help, /fixture profile/);
});

test("unsafe headers and policy-catalog provider manifest changes are rejected", async () => {
  const loaded = await loadCrawlerConfig({ argv: [], env: {}, warn: () => {} });

  const withCookie = structuredClone(loaded.config);
  withCookie.request.headers.cookie = "session=secret";
  assert.throws(() => validateCrawlerConfig(withCookie), ConfigError);

  const withHostChange = structuredClone(loaded.config);
  withHostChange.providers.naver.allowedHosts = [
    "api-gw.sports.naver.com",
    "unreviewed.example.com",
  ];
  assert.throws(() => validateCrawlerConfig(withHostChange), ConfigError);

  const withPathChange = structuredClone(loaded.config);
  withPathChange.providers.naver.schedule.urlTemplate =
    "https://api-gw.sports.naver.com/unreviewed?date={dateYYYYMMDD}";
  assert.throws(() => validateCrawlerConfig(withPathChange), ConfigError);
});

test("Naver relay text and scheduled persistent-state policy are enforced", async () => {
  const loaded = await loadCrawlerConfig({ argv: [], env: {}, warn: () => {} });
  const candidate = structuredClone(loaded.config);
  candidate.source = "naver";
  candidate.identity.contact = "mailto:operator@example.com";
  candidate.providers.naver.enabled = true;
  candidate.providers.naver.authorization = {
    status: "approved",
    evidence: "written-permission-reference",
    reviewedAt: "2026-08-25T00:00:00+09:00",
    expiresAt: "2026-09-25T00:00:00+09:00",
    scopes: ["schedule", "relay", "record", "robots-disallow-override"],
    robotsExceptionGranted: true,
    undocumentedApiApproved: true,
  };
  candidate.policy.allowUndocumentedApi = true;
  candidate.data.includePlayDescriptions = true;

  let report = evaluateCrawlerPolicy(validateCrawlerConfig(candidate), {
    now: new Date("2026-08-26T00:00:00+09:00"),
  });
  assert.ok(
    report.errors.some((entry) => entry.code === "RELAY_TEXT_SCOPE_REQUIRED")
  );

  candidate.providers.naver.authorization.scopes.push("relay-text");
  report = evaluateCrawlerPolicy(validateCrawlerConfig(candidate), {
    now: new Date("2026-08-26T00:00:00+09:00"),
  });
  assert.equal(report.ok, true);

  candidate.timezone = "UTC";
  report = evaluateCrawlerPolicy(validateCrawlerConfig(candidate), {
    now: new Date("2026-08-26T00:00:00+09:00"),
  });
  assert.ok(
    report.errors.some((entry) => entry.code === "EXTERNAL_TIMEZONE_REQUIRED")
  );
  candidate.timezone = "Asia/Seoul";

  candidate.run.mode = "scheduled";
  candidate.output.enabled = false;
  report = evaluateCrawlerPolicy(validateCrawlerConfig(candidate), {
    now: new Date("2026-08-26T00:00:00+09:00"),
  });
  assert.ok(
    report.errors.some(
      (entry) => entry.code === "PERSISTENT_POLL_STATE_REQUIRED"
    )
  );
});
