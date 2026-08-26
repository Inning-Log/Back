import { promises as fs } from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";
import { parseDocument, parse as parseYamlValue } from "yaml";
import { z } from "zod";

export const PROJECT_ROOT = path.resolve(
  path.dirname(fileURLToPath(import.meta.url)),
  ".."
);
export const DEFAULT_CONFIG_PATH = path.join(
  PROJECT_ROOT,
  "config",
  "crawler.yml"
);
export const DEFAULT_PROFILE = "fixture";

const REDACTED = "[REDACTED]";
const MAX_CONFIG_BYTES = 256 * 1024;
const SAFE_PATH_PART = /^[A-Za-z_][A-Za-z0-9_-]*$/;
const PROFILE_NAME = /^[a-z][a-z0-9_-]{0,31}$/;
const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/;
const HOST_PATTERN = /^(?=.{1,253}$)(?!-)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;
const TEMPLATE_PATTERN = /\{([A-Za-z][A-Za-z0-9_]*)\}/g;
const ALLOWED_PLACEHOLDERS = new Set([
  "date",
  "dateYYYYMMDD",
  "dateYYYY_MM_DD",
  "gameId",
]);
const SAFE_REQUEST_HEADER_NAMES = new Set(["accept", "accept-language"]);
const EXTERNAL_PROVIDER_MANIFEST = Object.freeze({
  kbo: Object.freeze({
    allowedHosts: Object.freeze(["www.koreabaseball.com"]),
    robotsUrl: "https://www.koreabaseball.com/robots.txt",
    termsUrl: "https://www.koreabaseball.com/Etc/Policy.aspx",
    endpoints: Object.freeze({
      schedule:
        "https://www.koreabaseball.com/ws/Main.asmx/GetKboGameList?date={dateYYYYMMDD}",
      relay: null,
      record: null,
    }),
  }),
  naver: Object.freeze({
    allowedHosts: Object.freeze(["api-gw.sports.naver.com"]),
    robotsUrl: "https://api-gw.sports.naver.com/robots.txt",
    termsUrl: "https://policy.naver.com/rules/disclaimer.html",
    endpoints: Object.freeze({
      schedule:
        "https://api-gw.sports.naver.com/schedule/games?date={dateYYYYMMDD}",
      relay:
        "https://api-gw.sports.naver.com/schedule/games/{gameId}/relay",
      record:
        "https://api-gw.sports.naver.com/schedule/games/{gameId}/record",
    }),
  }),
});

export class ConfigError extends Error {
  constructor(code, message, issues = []) {
    super(message);
    this.name = "ConfigError";
    this.code = code;
    this.issues = issues;
  }
}

function issue(pathValue, message, code = "custom") {
  return {
    code,
    path: Array.isArray(pathValue) ? pathValue : String(pathValue).split("."),
    message,
  };
}

function isPlainObject(value) {
  if (value === null || typeof value !== "object" || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

function clone(value) {
  if (Array.isArray(value)) return value.map(clone);
  if (!isPlainObject(value)) return value;
  return Object.fromEntries(
    Object.entries(value).map(([key, entry]) => [key, clone(entry)])
  );
}

export function deepMerge(...layers) {
  let merged = {};
  for (const layer of layers) {
    if (!isPlainObject(layer)) {
      throw new ConfigError(
        "INVALID_CONFIG_LAYER",
        "Configuration layers must be plain objects."
      );
    }
    merged = mergeObject(merged, layer);
  }
  return merged;
}

function mergeObject(target, source) {
  const next = clone(target);
  for (const [key, value] of Object.entries(source)) {
    if (["__proto__", "prototype", "constructor"].includes(key)) {
      throw new ConfigError(
        "UNSAFE_CONFIG_PATH",
        `Unsafe configuration key: ${key}`
      );
    }
    if (isPlainObject(value) && isPlainObject(next[key])) {
      next[key] = mergeObject(next[key], value);
    } else {
      next[key] = clone(value);
    }
  }
  return next;
}

export function parseDuration(value) {
  if (typeof value === "number") {
    if (!Number.isFinite(value) || !Number.isInteger(value) || value < 0) {
      throw new TypeError("Duration numbers must be non-negative integer milliseconds.");
    }
    return value;
  }
  if (typeof value !== "string" || value.trim() === "") {
    throw new TypeError("Duration must be milliseconds or text such as '800ms', '5s', or '2m 30s'.");
  }

  const input = value.trim().toLowerCase();
  if (/^\d+$/.test(input)) return Number.parseInt(input, 10);

  const unitMs = {
    ms: 1,
    s: 1_000,
    m: 60_000,
    h: 3_600_000,
    d: 86_400_000,
  };
  const token = /(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)/gy;
  let total = 0;
  let cursor = 0;
  let count = 0;

  while (cursor < input.length) {
    while (input[cursor] === " ") cursor += 1;
    token.lastIndex = cursor;
    const match = token.exec(input);
    if (!match) {
      throw new TypeError(`Invalid duration: ${value}`);
    }
    total += Number.parseFloat(match[1]) * unitMs[match[2]];
    cursor = token.lastIndex;
    count += 1;
  }

  if (count === 0 || !Number.isSafeInteger(total)) {
    throw new TypeError(`Invalid duration: ${value}`);
  }
  return total;
}

export function formatDuration(milliseconds) {
  if (!Number.isSafeInteger(milliseconds) || milliseconds < 0) {
    throw new TypeError("Duration must be a non-negative integer.");
  }
  if (milliseconds === 0) return "0ms";
  const parts = [];
  let remainder = milliseconds;
  for (const [suffix, size] of [
    ["d", 86_400_000],
    ["h", 3_600_000],
    ["m", 60_000],
    ["s", 1_000],
    ["ms", 1],
  ]) {
    const amount = Math.floor(remainder / size);
    if (amount > 0) {
      parts.push(`${amount}${suffix}`);
      remainder -= amount * size;
    }
  }
  return parts.join(" ");
}

function durationSchema(name, minimum, maximum) {
  return z
    .union([z.string(), z.number()])
    .transform((value, context) => {
      try {
        return parseDuration(value);
      } catch (error) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          message: `${name}: ${error.message}`,
        });
        return z.NEVER;
      }
    })
    .refine((value) => value >= minimum, {
      message: `${name} must be at least ${formatDuration(minimum)}.`,
    })
    .refine((value) => value <= maximum, {
      message: `${name} must not exceed ${formatDuration(maximum)}.`,
    });
}

function integerSchema(name, minimum, maximum) {
  return z.preprocess(
    (value) => {
      if (typeof value === "string" && /^-?\d+$/.test(value.trim())) {
        return Number.parseInt(value, 10);
      }
      return value;
    },
    z
      .number({ invalid_type_error: `${name} must be an integer.` })
      .int(`${name} must be an integer.`)
      .min(minimum, `${name} must be at least ${minimum}.`)
      .max(maximum, `${name} must not exceed ${maximum}.`)
  );
}

const StrictBooleanSchema = z.preprocess((value) => {
  if (typeof value !== "string") return value;
  const normalized = value.trim().toLowerCase();
  if (["true", "1", "yes", "on"].includes(normalized)) return true;
  if (["false", "0", "no", "off"].includes(normalized)) return false;
  return value;
}, z.boolean());

const RequestHeadersSchema = z
  .record(
    z
      .string()
      .trim()
      .min(1)
      .max(2_000)
      .refine((value) => !/[\r\n]/.test(value), "Header values cannot contain CR/LF.")
  )
  .superRefine((headers, context) => {
    for (const name of Object.keys(headers)) {
      if (!SAFE_REQUEST_HEADER_NAMES.has(name.toLowerCase())) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: [name],
          message:
            "Only accept and accept-language may be customized. User-Agent comes from identity; cookies and authorization headers are forbidden.",
        });
      }
    }
  });

export function isStrictDate(value) {
  if (typeof value !== "string") return false;
  const match = DATE_PATTERN.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const date = new Date(Date.UTC(year, month - 1, day));
  return (
    date.getUTCFullYear() === year &&
    date.getUTCMonth() === month - 1 &&
    date.getUTCDate() === day
  );
}

export function isIanaTimezone(value) {
  if (typeof value !== "string") return false;
  if (value !== "UTC" && !/^[A-Za-z_]+(?:\/[A-Za-z0-9_+\-]+)+$/.test(value)) {
    return false;
  }
  try {
    new Intl.DateTimeFormat("en-US", { timeZone: value }).format();
    return true;
  } catch {
    return false;
  }
}

function expandCronPart(part, minimum, maximum) {
  const values = new Set();
  for (const rawSegment of part.split(",")) {
    if (!rawSegment) return null;
    const [base, rawStep, ...extra] = rawSegment.split("/");
    if (extra.length > 0) return null;
    const step = rawStep === undefined ? 1 : Number(rawStep);
    if (!Number.isInteger(step) || step < 1 || step > maximum - minimum + 1) {
      return null;
    }

    let start;
    let end;
    if (base === "*") {
      start = minimum;
      end = maximum;
    } else if (/^\d+$/.test(base)) {
      start = Number(base);
      end = rawStep === undefined ? start : maximum;
    } else {
      const range = /^(\d+)-(\d+)$/.exec(base);
      if (!range) return null;
      start = Number(range[1]);
      end = Number(range[2]);
    }

    if (start < minimum || end > maximum || start > end) return null;
    for (let value = start; value <= end; value += step) values.add(value);
  }
  return values;
}

export function validateFiveFieldCron(expression) {
  if (typeof expression !== "string") {
    return { ok: false, message: "Cron expression must be text." };
  }
  const fields = expression.trim().split(/\s+/);
  if (fields.length !== 5) {
    return {
      ok: false,
      message: "Cron must have exactly 5 fields (minute resolution; no seconds field).",
    };
  }
  const ranges = [
    [0, 59, "minute"],
    [0, 23, "hour"],
    [1, 31, "day-of-month"],
    [1, 12, "month"],
    [0, 7, "day-of-week"],
  ];
  for (let index = 0; index < fields.length; index += 1) {
    if (!expandCronPart(fields[index], ranges[index][0], ranges[index][1])) {
      return {
        ok: false,
        message: `Invalid ${ranges[index][2]} cron field: ${fields[index]}`,
      };
    }
  }
  return {
    ok: true,
    minimumIntervalMs: 60_000,
    message: "Valid 5-field cron with a minimum resolution of one minute.",
  };
}

function isValidHost(host) {
  return (
    typeof host === "string" &&
    host === host.toLowerCase() &&
    !host.includes("*") &&
    HOST_PATTERN.test(host)
  );
}

function collectPlaceholders(template) {
  const placeholders = [];
  for (const match of template.matchAll(TEMPLATE_PATTERN)) {
    placeholders.push(match[1]);
  }
  return placeholders;
}

export function validateUrlTemplate(
  template,
  { allowedHosts = [], requiredPlaceholders = [] } = {}
) {
  const issues = [];
  if (typeof template !== "string" || template.trim() === "") {
    return { ok: false, issues: ["URL template must be non-empty text."] };
  }
  const placeholders = collectPlaceholders(template);
  for (const placeholder of placeholders) {
    if (!ALLOWED_PLACEHOLDERS.has(placeholder)) {
      issues.push(`Unknown URL placeholder: {${placeholder}}`);
    }
  }
  for (const required of requiredPlaceholders) {
    if (!placeholders.includes(required)) {
      issues.push(`Required URL placeholder is missing: {${required}}`);
    }
  }

  const materialized = template.replace(TEMPLATE_PATTERN, (_, key) => {
    if (key === "gameId") return "TEST_GAME_ID";
    if (key === "dateYYYYMMDD") return "20260101";
    if (key === "dateYYYY_MM_DD" || key === "date") return "2026-01-01";
    return "INVALID";
  });

  try {
    const url = new URL(materialized);
    if (url.protocol !== "https:") issues.push("URL must use HTTPS.");
    if (url.username || url.password) {
      issues.push("Credentials must not be embedded in a URL.");
    }
    for (const key of url.searchParams.keys()) {
      if (/(?:token|secret|password|api[_-]?key|cookie|session|credential|authorization)/i.test(key)) {
        issues.push(`Sensitive URL query parameters are forbidden: ${key}`);
      }
    }
    if (url.hash) issues.push("URL templates must not contain fragments.");
    const normalizedAllowed = allowedHosts.map((host) => host.toLowerCase());
    if (!normalizedAllowed.includes(url.hostname.toLowerCase())) {
      issues.push(`URL host is not allowlisted: ${url.hostname}`);
    }
  } catch {
    issues.push("URL template is not a valid absolute URL.");
  }
  return { ok: issues.length === 0, issues, placeholders };
}

function isHttpsUrl(value) {
  if (value === null) return true;
  if (typeof value !== "string") return false;
  try {
    const parsed = new URL(value);
    return (
      parsed.protocol === "https:" &&
      parsed.username === "" &&
      parsed.password === "" &&
      parsed.hash === ""
    );
  } catch {
    return false;
  }
}

function isCompilableRegex(value) {
  if (value === null) return true;
  try {
    new RegExp(value);
    return true;
  } catch {
    return false;
  }
}

function isValidBotContact(value) {
  if (typeof value !== "string" || /[\s()\r\n]/.test(value)) return false;
  if (/^mailto:[^@]+@[^@]+\.[^@]+$/i.test(value)) return true;
  try {
    const url = new URL(value);
    return (
      url.protocol === "https:" &&
      url.username === "" &&
      url.password === "" &&
      url.hash === ""
    );
  } catch {
    return false;
  }
}

const AuthorizationSchema = z
  .object({
    status: z.enum(["unverified", "approved"]),
    evidence: z.string().trim().min(1).max(2_000).nullable(),
    reviewedAt: z.string().datetime({ offset: true }).nullable(),
    expiresAt: z.string().datetime({ offset: true }).nullable(),
    scopes: z
      .array(
        z
          .string()
          .trim()
          .regex(
            /^[a-z][a-z0-9:-]{0,99}$/,
            "Scopes must be lowercase tokens using letters, digits, ':', or '-'."
          )
      )
      .max(30)
      .refine(
        (scopes) => new Set(scopes).size === scopes.length,
        "Authorization scopes must not contain duplicates."
      ),
    robotsExceptionGranted: StrictBooleanSchema,
    undocumentedApiApproved: StrictBooleanSchema,
  })
  .strict();

const EndpointSchema = z
  .object({
    urlTemplate: z.string().trim().min(1).max(2_000),
    method: z.literal("GET"),
    arrayPaths: z.array(z.string().trim().min(1).max(200)).min(1).max(30).optional(),
  })
  .strict();

const ScheduleSelectorsSchema = z
  .object({
    row: z.string().min(1),
    dateTime: z.string().min(1),
    homeTeam: z.string().min(1),
    awayTeam: z.string().min(1),
    stadium: z.string().min(1),
    status: z.string().min(1),
    homeScore: z.string().min(1),
    awayScore: z.string().min(1),
    inning: z.string().min(1),
    inningHalf: z.string().min(1),
  })
  .strict();

const ProviderKeysSchema = z
  .object({
    relayPlayCandidates: z.array(z.string().min(1)).min(1).max(20),
    inningArrayCandidates: z.array(z.string().min(1)).min(1).max(20),
  })
  .strict();

const ProviderSchema = z
  .object({
    enabled: StrictBooleanSchema,
    kind: z.enum(["fixture", "official-web", "undocumented-api"]),
    documentedApi: StrictBooleanSchema,
    allowedHosts: z
      .array(z.string().refine(isValidHost, "Host must be a lowercase DNS hostname without wildcards."))
      .max(20)
      .refine((hosts) => new Set(hosts).size === hosts.length, "allowedHosts must not contain duplicates."),
    robotsUrl: z
      .string()
      .nullable()
      .refine(isHttpsUrl, "robotsUrl must be an HTTPS URL without credentials or fragments."),
    termsUrl: z
      .string()
      .nullable()
      .refine(isHttpsUrl, "termsUrl must be an HTTPS URL without credentials or fragments."),
    authorization: AuthorizationSchema,
    file: z.string().trim().min(1).max(1_000).nullable(),
    schedule: EndpointSchema.nullable(),
    relay: EndpointSchema.nullable(),
    record: EndpointSchema.nullable(),
    scheduleSelectors: ScheduleSelectorsSchema.nullable(),
    gameIdFromHrefRegex: z
      .string()
      .min(1)
      .max(500)
      .nullable()
      .refine(isCompilableRegex, "gameIdFromHrefRegex must compile as a JavaScript regular expression."),
    keys: ProviderKeysSchema.nullable(),
  })
  .strict();

const BaseCrawlerConfigObjectSchema = z
  .object({
    enabled: StrictBooleanSchema,
    identity: z
      .object({
        product: z
          .string()
          .trim()
          .regex(
            /^[A-Za-z_-]{1,100}$/,
            "identity.product must be an RFC 9309 product token using letters, '_' or '-'."
          ),
        version: z.string().trim().regex(/^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$/),
        contact: z
          .string()
          .trim()
          .min(3)
          .max(300)
          .refine(
            isValidBotContact,
            "identity.contact must be a valid mailto: address or HTTPS URL without credentials, fragments, whitespace, or parentheses."
          ),
      })
      .strict(),
    timezone: z.string().refine(isIanaTimezone, "timezone must be a valid IANA name such as Asia/Seoul or UTC."),
    source: z.enum(["fixture", "kbo", "naver", "hybrid"]),
    date: z
      .string()
      .nullable()
      .refine((value) => value === null || isStrictDate(value), "date must be a real calendar date in YYYY-MM-DD format."),
    run: z
      .object({
        mode: z.enum(["once", "scheduled"]),
        cron: z.string().refine((value) => validateFiveFieldCron(value).ok, {
          message: "cron must be a valid 5-field expression with one-minute resolution.",
        }),
        maxGamesPerRun: integerSchema("maxGamesPerRun", 1, 20),
        maxDetailGamesPerRun: integerSchema("maxDetailGamesPerRun", 0, 5),
      })
      .strict(),
    output: z
      .object({
        directory: z.string().trim().min(1).max(1_000),
        filePrefix: z.string().regex(/^[a-z0-9][a-z0-9_-]{0,63}$/),
        enabled: StrictBooleanSchema,
        forceWrite: StrictBooleanSchema,
        dryRun: StrictBooleanSchema,
      })
      .strict(),
    request: z
      .object({
        timeoutMs: durationSchema("request.timeoutMs", 1_000, 60_000),
        minHostIntervalMs: durationSchema("request.minHostIntervalMs", 2_000, 10 * 60_000),
        maxRequestsPerRun: integerSchema("request.maxRequestsPerRun", 1, 20),
        maxAttemptsPerRun: integerSchema("request.maxAttemptsPerRun", 1, 40),
        maxResponseBytes: integerSchema("request.maxResponseBytes", 1_024, 2 * 1024 * 1024),
        memoryCacheTtlMs: durationSchema(
          "request.memoryCacheTtlMs",
          60_000,
          24 * 60 * 60_000
        ),
        jitterRatio: z.preprocess(
          (value) =>
            typeof value === "string" && value.trim() !== ""
              ? Number(value)
              : value,
          z.number().min(0.1).max(0.2)
        ),
        retry: z
          .object({
            count: integerSchema("request.retry.count", 0, 3),
            baseDelayMs: durationSchema("request.retry.baseDelayMs", 250, 60_000),
            maxDelayMs: durationSchema("request.retry.maxDelayMs", 1_000, 5 * 60_000),
          })
          .strict(),
        circuitBreaker: z
          .object({
            failureThreshold: integerSchema("request.circuitBreaker.failureThreshold", 1, 10),
            cooldownMs: durationSchema(
              "request.circuitBreaker.cooldownMs",
              60_000,
              24 * 60 * 60_000
            ),
          })
          .strict(),
        headers: RequestHeadersSchema,
      })
      .strict(),
    data: z
      .object({
        maxEventsPerGame: integerSchema("data.maxEventsPerGame", 0, 200),
        includePlayDescriptions: StrictBooleanSchema,
      })
      .strict(),
    strategy: z
      .object({
        detailOnlyStates: z
          .array(
            z.enum([
              "LIVE",
              "FINISHED",
              "POSTPONED",
              "DELAYED",
              "SUSPENDED",
            ])
          )
          .min(1)
          .max(8)
          .refine(
            (states) => new Set(states).size === states.length,
            "detailOnlyStates must not contain duplicates."
          ),
        polling: z
          .object({
            farFutureMs: durationSchema("strategy.polling.farFutureMs", 60 * 60_000, 7 * 86_400_000),
            gameDayMs: durationSchema("strategy.polling.gameDayMs", 5 * 60_000, 24 * 60 * 60_000),
            nearStartMs: durationSchema("strategy.polling.nearStartMs", 60_000, 60 * 60_000),
            liveScheduleMs: durationSchema("strategy.polling.liveScheduleMs", 60_000, 60 * 60_000),
            liveDetailMs: durationSchema("strategy.polling.liveDetailMs", 60_000, 60 * 60_000),
            delayedDetailMs: durationSchema("strategy.polling.delayedDetailMs", 2 * 60_000, 6 * 60 * 60_000),
            finishedOffsetsMs: z
              .array(durationSchema("strategy.polling.finishedOffsetsMs", 5 * 60_000, 24 * 60 * 60_000))
              .min(1)
              .max(10)
              .refine(
                (offsets) => offsets.every(
                  (offset, index) => index === 0 || offset > offsets[index - 1]
                ),
                "finishedOffsetsMs must be strictly increasing."
              ),
          })
          .strict(),
      })
      .strict(),
    policy: z
      .object({
        enforcement: z.literal("strict"),
        killSwitch: StrictBooleanSchema,
        failClosed: StrictBooleanSchema,
        requireHttps: StrictBooleanSchema,
        respectRobotsTxt: StrictBooleanSchema,
        robotsCacheTtlMs: durationSchema(
          "robotsCacheTtlMs",
          15 * 60_000,
          24 * 60 * 60_000
        ),
        authorizationReviewMaxAgeMs: durationSchema(
          "authorizationReviewMaxAgeMs",
          86_400_000,
          30 * 86_400_000
        ),
        allowUndocumentedApi: StrictBooleanSchema,
      })
      .strict(),
    providers: z
      .object({
        fixture: ProviderSchema,
        kbo: ProviderSchema,
        naver: ProviderSchema,
      })
      .strict(),
  })
  .strict();

function addTemplateIssues(context, config, providerName, endpointName, required) {
  const provider = config.providers[providerName];
  const endpoint = provider[endpointName];
  if (!endpoint) return;
  const result = validateUrlTemplate(endpoint.urlTemplate, {
    allowedHosts: provider.allowedHosts,
    requiredPlaceholders: required,
  });
  for (const message of result.issues) {
    context.addIssue({
      code: z.ZodIssueCode.custom,
      path: ["providers", providerName, endpointName, "urlTemplate"],
      message,
    });
  }
}

export const CrawlerConfigSchema = BaseCrawlerConfigObjectSchema.superRefine(
  (config, context) => {
    if (config.run.maxDetailGamesPerRun > config.run.maxGamesPerRun) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["run", "maxDetailGamesPerRun"],
        message: "maxDetailGamesPerRun cannot exceed maxGamesPerRun.",
      });
    }
    if (config.request.maxAttemptsPerRun < config.request.maxRequestsPerRun) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["request", "maxAttemptsPerRun"],
        message: "maxAttemptsPerRun cannot be lower than maxRequestsPerRun.",
      });
    }
    if (config.request.retry.maxDelayMs < config.request.retry.baseDelayMs) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["request", "retry", "maxDelayMs"],
        message: "retry.maxDelayMs cannot be lower than retry.baseDelayMs.",
      });
    }
    const polling = config.strategy.polling;
    if (
      polling.nearStartMs > polling.gameDayMs ||
      polling.gameDayMs > polling.farFutureMs
    ) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["strategy", "polling"],
        message:
          "Polling must become no more frequent as a game gets farther away: nearStartMs <= gameDayMs <= farFutureMs.",
      });
    }
    if (polling.liveDetailMs < polling.liveScheduleMs) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["strategy", "polling", "liveDetailMs"],
        message: "liveDetailMs cannot be shorter than liveScheduleMs.",
      });
    }
    if (polling.delayedDetailMs < polling.liveDetailMs) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["strategy", "polling", "delayedDetailMs"],
        message: "delayedDetailMs cannot be shorter than liveDetailMs.",
      });
    }
    if (config.output.dryRun && config.output.enabled) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["output", "enabled"],
        message: "output.enabled must be false in dry-run mode.",
      });
    }
    if (config.output.forceWrite && (!config.output.enabled || config.output.dryRun)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["output", "forceWrite"],
        message: "forceWrite conflicts with disabled output or dry-run mode.",
      });
    }

    addTemplateIssues(context, config, "kbo", "schedule", ["dateYYYYMMDD"]);
    addTemplateIssues(context, config, "naver", "schedule", ["dateYYYYMMDD"]);
    addTemplateIssues(context, config, "naver", "relay", ["gameId"]);
    addTemplateIssues(context, config, "naver", "record", ["gameId"]);

    for (const providerName of ["kbo", "naver"]) {
      const provider = config.providers[providerName];
      const manifest = EXTERNAL_PROVIDER_MANIFEST[providerName];
      if (provider.kind !== "undocumented-api" || provider.documentedApi !== false) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["providers", providerName, "kind"],
          message:
            "External provider classification is policy-catalog owned and currently must remain undocumented-api/documentedApi=false.",
        });
      }
      if (
        provider.allowedHosts.length !== manifest.allowedHosts.length ||
        provider.allowedHosts.some(
          (host, index) => host !== manifest.allowedHosts[index]
        )
      ) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["providers", providerName, "allowedHosts"],
          message:
            "External provider hosts are policy-catalog owned. Adding or changing a host requires a reviewed code change.",
        });
      }
      for (const field of ["robotsUrl", "termsUrl"]) {
        if (provider[field] !== manifest[field]) {
          context.addIssue({
            code: z.ZodIssueCode.custom,
            path: ["providers", providerName, field],
            message: `${providerName}.${field} is policy-catalog owned and must remain ${manifest[field]}.`,
          });
        }
      }
      for (const endpointName of ["schedule", "relay", "record"]) {
        const actual = provider[endpointName]?.urlTemplate ?? null;
        if (actual !== manifest.endpoints[endpointName]) {
          context.addIssue({
            code: z.ZodIssueCode.custom,
            path: [
              "providers",
              providerName,
              endpointName,
              "urlTemplate",
            ],
            message:
              "External endpoint paths are policy-catalog owned. Changing one requires written authorization and a reviewed code change.",
          });
        }
      }
      if (provider.file !== null) {
        context.addIssue({
          code: z.ZodIssueCode.custom,
          path: ["providers", providerName, "file"],
          message: "External providers cannot use the fixture file setting.",
        });
      }
      if (provider.authorization.status === "approved") {
        for (const field of ["evidence", "reviewedAt", "expiresAt"]) {
          if (!provider.authorization[field]) {
            context.addIssue({
              code: z.ZodIssueCode.custom,
              path: ["providers", providerName, "authorization", field],
              message: `${field} is required for approved external authorization.`,
            });
          }
        }
      }
      if (provider.robotsUrl) {
        const host = new URL(provider.robotsUrl).hostname.toLowerCase();
        if (!provider.allowedHosts.includes(host)) {
          context.addIssue({
            code: z.ZodIssueCode.custom,
            path: ["providers", providerName, "robotsUrl"],
            message: `robotsUrl host must be in ${providerName}.allowedHosts.`,
          });
        }
      }
    }

    const fixture = config.providers.fixture;
    if (
      fixture.kind !== "fixture" ||
      fixture.documentedApi !== true ||
      fixture.allowedHosts.length !== 0 ||
      fixture.robotsUrl !== null ||
      fixture.termsUrl !== null ||
      !fixture.file ||
      fixture.schedule !== null ||
      fixture.relay !== null ||
      fixture.record !== null ||
      fixture.scheduleSelectors !== null ||
      fixture.gameIdFromHrefRegex !== null ||
      fixture.keys !== null
    ) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["providers", "fixture"],
        message:
          "fixture is local-file-only and cannot declare external hosts, endpoints, selectors, or provider keys.",
      });
    }

    const kbo = config.providers.kbo;
    if (
      !kbo.schedule ||
      kbo.relay !== null ||
      kbo.record !== null ||
      !kbo.scheduleSelectors ||
      !kbo.gameIdFromHrefRegex ||
      kbo.keys !== null
    ) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["providers", "kbo"],
        message:
          "KBO adapter requires schedule/selectors/game-id regex and forbids unused relay, record, and keys settings.",
      });
    }

    const naver = config.providers.naver;
    if (
      !naver.schedule ||
      !naver.relay ||
      !naver.record ||
      naver.scheduleSelectors !== null ||
      naver.gameIdFromHrefRegex !== null ||
      !naver.keys
    ) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["providers", "naver"],
        message:
          "Naver adapter requires schedule/relay/record and detail keys, and forbids unused HTML settings.",
      });
    }
  }
);

export const CrawlerProfileSchema = BaseCrawlerConfigObjectSchema.deepPartial();

export const CrawlerConfigFileSchema = z
  .object({
    version: z.literal(1),
    defaultProfile: z.string().regex(PROFILE_NAME),
    base: CrawlerConfigSchema,
    profiles: z.record(z.string().regex(PROFILE_NAME), CrawlerProfileSchema),
  })
  .strict()
  .superRefine((document, context) => {
    if (!Object.hasOwn(document.profiles, document.defaultProfile)) {
      context.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["defaultProfile"],
        message: `Default profile does not exist: ${document.defaultProfile}`,
      });
    }
  });

function zodIssues(error) {
  return error.issues.map((entry) => ({
    path: entry.path.join("."),
    message: entry.message,
    code: entry.code,
  }));
}

function throwZodError(code, label, error) {
  const issues = zodIssues(error);
  const detail = issues.map((entry) => `${entry.path || "<root>"}: ${entry.message}`).join("\n");
  throw new ConfigError(code, `${label} is invalid:\n${detail}`, issues);
}

export function resolveProjectPath(value, projectRoot = PROJECT_ROOT) {
  if (typeof value !== "string" || value.trim() === "") {
    throw new ConfigError("INVALID_PATH", "Path must be non-empty text.");
  }
  return path.isAbsolute(value)
    ? path.normalize(value)
    : path.resolve(projectRoot, value);
}

export function isPathInsideProject(candidate, projectRoot = PROJECT_ROOT) {
  const relative = path.relative(path.resolve(projectRoot), path.resolve(candidate));
  return relative === "" || (!relative.startsWith("..") && !path.isAbsolute(relative));
}

export function validateCrawlerConfig(input, { projectRoot = PROJECT_ROOT } = {}) {
  const result = CrawlerConfigSchema.safeParse(input);
  if (!result.success) {
    throwZodError("CONFIG_VALIDATION_FAILED", "Crawler configuration", result.error);
  }
  const config = clone(result.data);
  config.output.directory = resolveProjectPath(config.output.directory, projectRoot);
  if (!isPathInsideProject(config.output.directory, projectRoot)) {
    throw new ConfigError(
      "OUTPUT_OUTSIDE_PROJECT",
      `Output directory must stay inside the crawler project root: ${projectRoot}`,
      [issue("output.directory", "Path resolves outside the crawler project root.")]
    );
  }
  if (config.providers.fixture.file) {
    config.providers.fixture.file = resolveProjectPath(
      config.providers.fixture.file,
      projectRoot
    );
    if (!isPathInsideProject(config.providers.fixture.file, projectRoot)) {
      throw new ConfigError(
        "FIXTURE_OUTSIDE_PROJECT",
        `Fixture path must stay inside the crawler project root: ${projectRoot}`,
        [issue("providers.fixture.file", "Path resolves outside the crawler project root.")]
      );
    }
  }
  return config;
}

const ENV_META_KEYS = new Set([
  "CRAWLER_CONFIG",
  "CRAWLER_PROFILE",
  "CRAWLER_ENV_FILE",
]);

const ENV_BINDINGS = {
  CRAWLER_ENABLED: "enabled",
  CRAWLER_IDENTITY_CONTACT: "identity.contact",
  CRAWLER_TIMEZONE: "timezone",
  CRAWLER_SOURCE: "source",
  CRAWLER_DATE: "date",
  CRAWLER_MODE: "run.mode",
  CRAWLER_CRON: "run.cron",
  CRAWLER_MAX_GAMES: "run.maxGamesPerRun",
  CRAWLER_MAX_DETAILS: "run.maxDetailGamesPerRun",
  CRAWLER_MAX_EVENTS: "data.maxEventsPerGame",
  CRAWLER_INCLUDE_PLAY_DESCRIPTIONS: "data.includePlayDescriptions",
  CRAWLER_RETRY_COUNT: "request.retry.count",
  CRAWLER_RETRY_BASE_DELAY: "request.retry.baseDelayMs",
  CRAWLER_RETRY_MAX_DELAY: "request.retry.maxDelayMs",
  CRAWLER_OUTPUT_DIR: "output.directory",
  CRAWLER_OUTPUT_ENABLED: "output.enabled",
  CRAWLER_FORCE_WRITE: "output.forceWrite",
  CRAWLER_DRY_RUN: "output.dryRun",
  CRAWLER_TIMEOUT: "request.timeoutMs",
  CRAWLER_MIN_HOST_INTERVAL: "request.minHostIntervalMs",
  CRAWLER_MAX_REQUESTS_PER_RUN: "request.maxRequestsPerRun",
  CRAWLER_MAX_ATTEMPTS_PER_RUN: "request.maxAttemptsPerRun",
  CRAWLER_MAX_RESPONSE_BYTES: "request.maxResponseBytes",
  CRAWLER_MEMORY_CACHE_TTL: "request.memoryCacheTtlMs",
  CRAWLER_JITTER_RATIO: "request.jitterRatio",
  CRAWLER_CIRCUIT_FAILURE_THRESHOLD: "request.circuitBreaker.failureThreshold",
  CRAWLER_CIRCUIT_COOLDOWN: "request.circuitBreaker.cooldownMs",
  CRAWLER_KILL_SWITCH: "policy.killSwitch",
  CRAWLER_FAIL_CLOSED: "policy.failClosed",
  CRAWLER_REQUIRE_HTTPS: "policy.requireHttps",
  CRAWLER_RESPECT_ROBOTS: "policy.respectRobotsTxt",
  CRAWLER_ROBOTS_CACHE_TTL: "policy.robotsCacheTtlMs",
  CRAWLER_AUTH_REVIEW_MAX_AGE: "policy.authorizationReviewMaxAgeMs",
  CRAWLER_ALLOW_UNDOCUMENTED_API: "policy.allowUndocumentedApi",
  CRAWLER_FIXTURE_ENABLED: "providers.fixture.enabled",
  CRAWLER_FIXTURE_FILE: "providers.fixture.file",
  CRAWLER_KBO_ENABLED: "providers.kbo.enabled",
  CRAWLER_KBO_AUTHORIZATION_STATUS: "providers.kbo.authorization.status",
  CRAWLER_KBO_AUTHORIZATION_EVIDENCE: "providers.kbo.authorization.evidence",
  CRAWLER_KBO_AUTHORIZATION_REVIEWED_AT: "providers.kbo.authorization.reviewedAt",
  CRAWLER_KBO_AUTHORIZATION_EXPIRES_AT: "providers.kbo.authorization.expiresAt",
  CRAWLER_KBO_AUTHORIZATION_SCOPES: "providers.kbo.authorization.scopes",
  CRAWLER_KBO_ROBOTS_EXCEPTION: "providers.kbo.authorization.robotsExceptionGranted",
  CRAWLER_KBO_UNDOCUMENTED_API_APPROVED: "providers.kbo.authorization.undocumentedApiApproved",
  CRAWLER_NAVER_ENABLED: "providers.naver.enabled",
  CRAWLER_NAVER_AUTHORIZATION_STATUS: "providers.naver.authorization.status",
  CRAWLER_NAVER_AUTHORIZATION_EVIDENCE: "providers.naver.authorization.evidence",
  CRAWLER_NAVER_AUTHORIZATION_REVIEWED_AT: "providers.naver.authorization.reviewedAt",
  CRAWLER_NAVER_AUTHORIZATION_EXPIRES_AT: "providers.naver.authorization.expiresAt",
  CRAWLER_NAVER_AUTHORIZATION_SCOPES: "providers.naver.authorization.scopes",
  CRAWLER_NAVER_ROBOTS_EXCEPTION: "providers.naver.authorization.robotsExceptionGranted",
  CRAWLER_NAVER_UNDOCUMENTED_API_APPROVED: "providers.naver.authorization.undocumentedApiApproved",
};

const LEGACY_ENV_BINDINGS = {
  SCRAPER_MAX_GAMES: "run.maxGamesPerRun",
  SCRAPER_MAX_DETAILS: "run.maxDetailGamesPerRun",
  SCRAPER_DATE: "date",
  SCRAPER_OUTPUT_DIR: "output.directory",
  SCRAPER_CRON: "run.cron",
  SCRAPER_SOURCE: "source",
  SCRAPER_TIMEOUT_MS: "request.timeoutMs",
  SCRAPER_FORCE: "output.forceWrite",
};

const LEGACY_META_KEYS = {
  SCRAPER_CONFIG: "CRAWLER_CONFIG",
  SCRAPER_PROFILE: "CRAWLER_PROFILE",
  SCRAPER_ENV_FILE: "CRAWLER_ENV_FILE",
};

function setDottedPath(target, dottedPath, value) {
  const parts = dottedPath.split(".");
  if (
    parts.length === 0 ||
    parts.some(
      (part) =>
        !SAFE_PATH_PART.test(part) ||
        ["__proto__", "prototype", "constructor"].includes(part)
    )
  ) {
    throw new ConfigError(
      "INVALID_SET_PATH",
      `Invalid configuration path: ${dottedPath}`
    );
  }
  let cursor = target;
  for (let index = 0; index < parts.length - 1; index += 1) {
    const part = parts[index];
    if (!isPlainObject(cursor[part])) cursor[part] = {};
    cursor = cursor[part];
  }
  cursor[parts.at(-1)] = value;
}

function parseSetValue(rawValue) {
  if (rawValue === "") return "";
  try {
    const parsed = parseYamlValue(rawValue, { maxAliasCount: 0 });
    if (parsed === undefined || typeof parsed === "function") {
      throw new Error("unsupported value");
    }
    if (isPlainObject(parsed)) {
      throw new Error("mapping values are not allowed; set a leaf or array value");
    }
    return parsed;
  } catch (error) {
    throw new ConfigError(
      "INVALID_SET_VALUE",
      `Invalid --set value '${rawValue}': ${error.message}`
    );
  }
}

function parseSetAssignment(raw) {
  const separator = raw.indexOf("=");
  if (separator <= 0) {
    throw new ConfigError(
      "MISSING_SET_VALUE",
      "--set requires PATH=VALUE (for example --set request.timeoutMs=15s)."
    );
  }
  const pathValue = raw.slice(0, separator).trim();
  const rawValue = raw.slice(separator + 1).trim();
  return { path: pathValue, value: parseSetValue(rawValue) };
}

function splitLongOption(arg) {
  const separator = arg.indexOf("=");
  if (separator === -1) return { name: arg, inlineValue: undefined };
  return {
    name: arg.slice(0, separator),
    inlineValue: arg.slice(separator + 1),
  };
}

function requireCliValue(rawArgs, index, name, inlineValue) {
  if (inlineValue !== undefined) {
    if (inlineValue === "") {
      throw new ConfigError("MISSING_ARGUMENT_VALUE", `${name} requires a value.`);
    }
    return { value: inlineValue, consumed: 0 };
  }
  const candidate = rawArgs[index + 1];
  if (candidate === undefined || candidate.startsWith("--")) {
    throw new ConfigError("MISSING_ARGUMENT_VALUE", `${name} requires a value.`);
  }
  return { value: candidate, consumed: 1 };
}

export function parseCliArgs(rawArgs = process.argv.slice(2)) {
  if (!Array.isArray(rawArgs)) {
    throw new ConfigError("INVALID_ARGUMENTS", "CLI arguments must be an array.");
  }
  const parsed = {
    configPath: null,
    profile: null,
    envFile: null,
    overrides: {},
    actions: {
      printConfig: false,
      checkConfig: false,
      checkPolicy: false,
      help: false,
    },
    supplied: [],
  };
  const seenOptions = new Set();
  const touchedPaths = new Map();
  let runModeOption = null;
  let writeModeOption = null;

  const markOption = (semantic, display) => {
    if (seenOptions.has(semantic)) {
      throw new ConfigError("DUPLICATE_ARGUMENT", `Duplicate argument: ${display}`);
    }
    seenOptions.add(semantic);
    parsed.supplied.push(display);
  };
  const touchPath = (configPath, display) => {
    if (touchedPaths.has(configPath)) {
      throw new ConfigError(
        "DUPLICATE_OVERRIDE",
        `${display} duplicates ${touchedPaths.get(configPath)} for ${configPath}.`
      );
    }
    touchedPaths.set(configPath, display);
  };
  const override = (configPath, value, display) => {
    touchPath(configPath, display);
    setDottedPath(parsed.overrides, configPath, value);
  };

  for (let index = 0; index < rawArgs.length; index += 1) {
    const rawArg = rawArgs[index];
    if (typeof rawArg !== "string" || !rawArg.startsWith("--")) {
      throw new ConfigError(
        "UNKNOWN_ARGUMENT",
        `Unexpected positional argument: ${String(rawArg)}`
      );
    }
    const { name, inlineValue } = splitLongOption(rawArg);

    if (["--config", "--profile", "--env-file", "--source", "--date", "--out", "--max-games", "--max-details"].includes(name)) {
      const semantic = name.slice(2);
      markOption(semantic, name);
      const found = requireCliValue(rawArgs, index, name, inlineValue);
      index += found.consumed;
      if (name === "--config") parsed.configPath = found.value;
      if (name === "--profile") parsed.profile = found.value;
      if (name === "--env-file") parsed.envFile = found.value;
      if (name === "--source") override("source", found.value, name);
      if (name === "--date") override("date", found.value, name);
      if (name === "--out") override("output.directory", found.value, name);
      if (name === "--max-games") override("run.maxGamesPerRun", found.value, name);
      if (name === "--max-details") override("run.maxDetailGamesPerRun", found.value, name);
      continue;
    }

    if (name === "--set") {
      const found = requireCliValue(rawArgs, index, name, inlineValue);
      index += found.consumed;
      const assignment = parseSetAssignment(found.value);
      touchPath(assignment.path, `--set ${assignment.path}`);
      setDottedPath(parsed.overrides, assignment.path, assignment.value);
      parsed.supplied.push(`--set ${assignment.path}`);
      continue;
    }

    if (name === "--once") {
      if (inlineValue !== undefined) throw new ConfigError("UNEXPECTED_ARGUMENT_VALUE", "--once does not accept a value.");
      markOption("once", name);
      if (runModeOption) throw new ConfigError("CONFLICTING_ARGUMENTS", `${name} conflicts with ${runModeOption}.`);
      runModeOption = name;
      override("run.mode", "once", name);
      continue;
    }

    if (name === "--cron" || name === "--scheduled") {
      markOption("scheduled", name);
      if (runModeOption) throw new ConfigError("CONFLICTING_ARGUMENTS", `${name} conflicts with ${runModeOption}.`);
      runModeOption = name;
      override("run.mode", "scheduled", name);
      let cronValue = inlineValue;
      if (cronValue === undefined && rawArgs[index + 1] && !rawArgs[index + 1].startsWith("--")) {
        cronValue = rawArgs[index + 1];
        index += 1;
      }
      if (cronValue === "") throw new ConfigError("MISSING_ARGUMENT_VALUE", `${name}= requires a cron expression.`);
      if (cronValue !== undefined) override("run.cron", cronValue, name);
      continue;
    }

    if (["--force-write", "--dry-run", "--no-write"].includes(name)) {
      if (inlineValue !== undefined) throw new ConfigError("UNEXPECTED_ARGUMENT_VALUE", `${name} does not accept a value.`);
      markOption(name.slice(2), name);
      if (name === "--force-write") {
        if (writeModeOption) throw new ConfigError("CONFLICTING_ARGUMENTS", `${name} conflicts with ${writeModeOption}.`);
        writeModeOption = name;
        override("output.forceWrite", true, name);
      } else {
        if (writeModeOption) throw new ConfigError("CONFLICTING_ARGUMENTS", `${name} conflicts with ${writeModeOption}.`);
        writeModeOption = name;
        override("output.enabled", false, name);
        if (name === "--dry-run") override("output.dryRun", true, name);
      }
      continue;
    }

    const actionByName = {
      "--print-config": "printConfig",
      "--check-config": "checkConfig",
      "--check-policy": "checkPolicy",
      "--help": "help",
    };
    if (actionByName[name]) {
      if (inlineValue !== undefined) throw new ConfigError("UNEXPECTED_ARGUMENT_VALUE", `${name} does not accept a value.`);
      markOption(actionByName[name], name);
      parsed.actions[actionByName[name]] = true;
      continue;
    }

    throw new ConfigError("UNKNOWN_ARGUMENT", `Unknown argument: ${name}`);
  }

  if (parsed.profile !== null && !PROFILE_NAME.test(parsed.profile)) {
    throw new ConfigError(
      "INVALID_PROFILE",
      "--profile must start with a lowercase letter and contain only lowercase letters, digits, '_' or '-'."
    );
  }
  return parsed;
}

export function parseEnvFile(content, sourceName = ".env") {
  const parsed = {};
  const seen = new Set();
  const lines = String(content).replace(/^\uFEFF/, "").split(/\r?\n/);
  for (let index = 0; index < lines.length; index += 1) {
    let line = lines[index].trim();
    if (!line || line.startsWith("#")) continue;
    if (line.startsWith("export ")) line = line.slice(7).trim();
    const match = /^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/.exec(line);
    if (!match) {
      throw new ConfigError(
        "INVALID_ENV_FILE",
        `${sourceName}:${index + 1}: expected KEY=VALUE.`
      );
    }
    const key = match[1];
    if (seen.has(key)) {
      throw new ConfigError(
        "DUPLICATE_ENV_KEY",
        `${sourceName}:${index + 1}: duplicate key ${key}.`
      );
    }
    seen.add(key);
    let value = match[2].trim();
    if (value.startsWith('"')) {
      try {
        value = JSON.parse(value);
      } catch {
        throw new ConfigError(
          "INVALID_ENV_FILE",
          `${sourceName}:${index + 1}: invalid double-quoted value.`
        );
      }
    } else if (value.startsWith("'")) {
      if (!value.endsWith("'") || value.length < 2) {
        throw new ConfigError(
          "INVALID_ENV_FILE",
          `${sourceName}:${index + 1}: unterminated single-quoted value.`
        );
      }
      value = value.slice(1, -1);
    } else {
      value = value.replace(/\s+#.*$/, "").trim();
    }
    parsed[key] = value;
  }
  return parsed;
}

function present(environment, key) {
  return Object.hasOwn(environment, key) && environment[key] !== undefined;
}

function normalizeNullableEnvValue(configPath, value) {
  if (configPath.endsWith(".authorization.scopes")) {
    const trimmed = String(value).trim();
    if (trimmed === "") return [];
    if (trimmed.startsWith("[")) {
      const parsed = parseSetValue(trimmed);
      if (!Array.isArray(parsed)) {
        throw new ConfigError(
          "INVALID_ENVIRONMENT_VALUE",
          `${configPath} must be a JSON/YAML array or a comma-separated list.`
        );
      }
      return parsed;
    }
    return trimmed
      .split(",")
      .map((entry) => entry.trim())
      .filter(Boolean);
  }
  if (
    value === "" &&
    [
      "date",
      "providers.kbo.authorization.evidence",
      "providers.kbo.authorization.reviewedAt",
      "providers.kbo.authorization.expiresAt",
      "providers.naver.authorization.evidence",
      "providers.naver.authorization.reviewedAt",
      "providers.naver.authorization.expiresAt",
    ].includes(configPath)
  ) {
    return null;
  }
  return value;
}

export function envToOverrides(environment, { warn = () => {} } = {}) {
  const overrides = {};
  const warnings = [];
  const appliedPaths = new Map();

  for (const key of Object.keys(environment)) {
    if (/^CRAWLER_/i.test(key) && !ENV_META_KEYS.has(key) && !Object.hasOwn(ENV_BINDINGS, key)) {
      throw new ConfigError("UNKNOWN_ENVIRONMENT_KEY", `Unknown CRAWLER_* variable: ${key}`);
    }
  }

  for (const [key, configPath] of Object.entries(ENV_BINDINGS)) {
    if (!present(environment, key)) continue;
    if (appliedPaths.has(configPath)) {
      throw new ConfigError(
        "DUPLICATE_ENV_OVERRIDE",
        `${key} duplicates ${appliedPaths.get(configPath)} for ${configPath}.`
      );
    }
    appliedPaths.set(configPath, key);
    setDottedPath(
      overrides,
      configPath,
      normalizeNullableEnvValue(configPath, environment[key])
    );
  }

  for (const [legacyKey, configPath] of Object.entries(LEGACY_ENV_BINDINGS)) {
    if (!present(environment, legacyKey)) continue;
    const replacement = Object.entries(ENV_BINDINGS).find(([, pathValue]) => pathValue === configPath)?.[0];
    const ignored = appliedPaths.has(configPath);
    const message = ignored
      ? `${legacyKey} is deprecated and ignored because ${appliedPaths.get(configPath)} is set.`
      : `${legacyKey} is deprecated; use ${replacement ?? `CRAWLER_* for ${configPath}`} instead.`;
    warnings.push(message);
    warn(message);
    if (!ignored) {
      appliedPaths.set(configPath, legacyKey);
      setDottedPath(
        overrides,
        configPath,
        normalizeNullableEnvValue(configPath, environment[legacyKey])
      );
    }
  }

  const dryRunValue = environment.CRAWLER_DRY_RUN;
  if (
    present(environment, "CRAWLER_DRY_RUN") &&
    ["true", "1", "yes", "on"].includes(String(dryRunValue).trim().toLowerCase()) &&
    !appliedPaths.has("output.enabled")
  ) {
    appliedPaths.set("output.enabled", "CRAWLER_DRY_RUN");
    setDottedPath(overrides, "output.enabled", false);
  }
  return { overrides, warnings };
}

function legacyMetaValue(environment, canonicalKey, warnings, warn) {
  const legacyEntry = Object.entries(LEGACY_META_KEYS).find(([, replacement]) => replacement === canonicalKey);
  if (present(environment, canonicalKey)) {
    if (legacyEntry && present(environment, legacyEntry[0])) {
      const message = `${legacyEntry[0]} is deprecated and ignored because ${canonicalKey} is set.`;
      warnings.push(message);
      warn(message);
    }
    return environment[canonicalKey];
  }
  if (!legacyEntry || !present(environment, legacyEntry[0])) return null;
  const message = `${legacyEntry[0]} is deprecated; use ${canonicalKey} instead.`;
  warnings.push(message);
  warn(message);
  return environment[legacyEntry[0]];
}

async function readEnvFile(filePath) {
  let content;
  try {
    content = await fs.readFile(filePath, "utf8");
  } catch (error) {
    throw new ConfigError(
      "ENV_FILE_READ_FAILED",
      `Cannot read env file ${filePath}: ${error.message}`
    );
  }
  return parseEnvFile(content, filePath);
}

export async function readCrawlerConfigFile(filePath) {
  let content;
  try {
    const stat = await fs.stat(filePath);
    if (stat.size > MAX_CONFIG_BYTES) {
      throw new ConfigError(
        "CONFIG_FILE_TOO_LARGE",
        `Configuration file exceeds ${MAX_CONFIG_BYTES} bytes: ${filePath}`
      );
    }
    content = await fs.readFile(filePath, "utf8");
  } catch (error) {
    if (error instanceof ConfigError) throw error;
    throw new ConfigError(
      "CONFIG_FILE_READ_FAILED",
      `Cannot read configuration file ${filePath}: ${error.message}`
    );
  }

  const document = parseDocument(content, {
    maxAliasCount: 10,
    prettyErrors: true,
    uniqueKeys: true,
  });
  if (document.errors.length > 0) {
    const messages = document.errors.map((entry) => entry.message);
    throw new ConfigError(
      "INVALID_YAML",
      `Invalid YAML in ${filePath}:\n${messages.join("\n")}`,
      messages.map((message) => issue("<yaml>", message))
    );
  }
  let raw;
  try {
    raw = document.toJS({ maxAliasCount: 10 });
  } catch (error) {
    throw new ConfigError(
      "INVALID_YAML",
      `Cannot materialize YAML in ${filePath}: ${error.message}`
    );
  }
  const result = CrawlerConfigFileSchema.safeParse(raw);
  if (!result.success) {
    throwZodError("CONFIG_FILE_VALIDATION_FAILED", `Configuration file ${filePath}`, result.error);
  }
  return result.data;
}

export async function loadCrawlerConfig({
  argv = process.argv.slice(2),
  env = process.env,
  projectRoot = PROJECT_ROOT,
  warn = (message) => console.warn(`[crawler config] ${message}`),
} = {}) {
  const cli = parseCliArgs(argv);
  const initialWarnings = [];
  const initialEnv = { ...env };
  const envFileSetting =
    cli.envFile ??
    legacyMetaValue(initialEnv, "CRAWLER_ENV_FILE", initialWarnings, warn);
  const envFilePath = envFileSetting
    ? resolveProjectPath(envFileSetting, projectRoot)
    : null;
  const fileEnvironment = envFilePath ? await readEnvFile(envFilePath) : {};
  const combinedEnvironment = { ...fileEnvironment, ...initialEnv };

  const configSetting =
    cli.configPath ??
    legacyMetaValue(combinedEnvironment, "CRAWLER_CONFIG", initialWarnings, warn) ??
    path.join(projectRoot, "config", "crawler.yml");
  const configPath = resolveProjectPath(configSetting, projectRoot);
  const document = await readCrawlerConfigFile(configPath);

  const selectedProfile =
    cli.profile ??
    legacyMetaValue(combinedEnvironment, "CRAWLER_PROFILE", initialWarnings, warn) ??
    document.defaultProfile;
  if (!PROFILE_NAME.test(selectedProfile)) {
    throw new ConfigError("INVALID_PROFILE", `Invalid profile name: ${selectedProfile}`);
  }
  if (!Object.hasOwn(document.profiles, selectedProfile)) {
    throw new ConfigError(
      "UNKNOWN_PROFILE",
      `Unknown profile '${selectedProfile}'. Available profiles: ${Object.keys(document.profiles).join(", ")}`
    );
  }

  const envResult = envToOverrides(combinedEnvironment, { warn });
  const merged = deepMerge(
    document.base,
    document.profiles[selectedProfile],
    envResult.overrides,
    cli.overrides
  );
  const config = validateCrawlerConfig(merged, { projectRoot });

  return {
    config,
    cli,
    profile: selectedProfile,
    warnings: [...initialWarnings, ...envResult.warnings],
    paths: {
      projectRoot: path.resolve(projectRoot),
      configFile: configPath,
      envFile: envFilePath,
    },
    precedence: ["yaml.base", `yaml.profiles.${selectedProfile}`, "environment", "cli"],
  };
}

function policyFinding(code, pathValue, message) {
  return { code, path: pathValue, message };
}

function requiredProvidersForSource(source) {
  if (source === "hybrid") return ["kbo", "naver"];
  if (source === "kbo" || source === "naver" || source === "fixture") return [source];
  return [];
}

function hasScope(authorization, scope) {
  return authorization.scopes.includes(scope);
}

export function evaluateCrawlerPolicy(config, { now = new Date() } = {}) {
  const errors = [];
  const warnings = [];
  const addError = (code, pathValue, message) =>
    errors.push(policyFinding(code, pathValue, message));
  const addWarning = (code, pathValue, message) =>
    warnings.push(policyFinding(code, pathValue, message));

  if (!config.enabled) {
    addError("CRAWLER_DISABLED", "enabled", "The global crawler switch is disabled.");
  }
  if (config.policy.killSwitch && config.source !== "fixture") {
    addError("KILL_SWITCH_ACTIVE", "policy.killSwitch", "The policy kill switch is active.");
  }
  if (!config.policy.failClosed) {
    addError("FAIL_CLOSED_REQUIRED", "policy.failClosed", "Strict operation requires failClosed=true.");
  }
  if (!config.policy.requireHttps) {
    addError("HTTPS_REQUIRED", "policy.requireHttps", "Strict operation requires HTTPS-only requests.");
  }

  if (
    config.run.mode === "scheduled" &&
    config.source !== "fixture" &&
    !config.output.enabled
  ) {
    addError(
      "PERSISTENT_POLL_STATE_REQUIRED",
      "output.enabled",
      "Scheduled external crawling requires persistent poll state; output.enabled cannot be false."
    );
  }

  const requiredProviders = requiredProvidersForSource(config.source);
  if (
    requiredProviders.some((providerName) => providerName !== "fixture") &&
    config.timezone !== "Asia/Seoul"
  ) {
    addError(
      "EXTERNAL_TIMEZONE_REQUIRED",
      "timezone",
      "KBO/Naver game dates are normalized in Asia/Seoul; external sources require timezone=Asia/Seoul."
    );
  }
  if (
    requiredProviders.some((providerName) => providerName !== "fixture") &&
    /example\.invalid/i.test(config.identity.contact)
  ) {
    addError(
      "BOT_CONTACT_PLACEHOLDER",
      "identity.contact",
      "External crawling requires a real operator contact; example.invalid is only a fixture placeholder."
    );
  }
  for (const providerName of requiredProviders) {
    const provider = config.providers[providerName];
    const prefix = `providers.${providerName}`;
    if (!provider.enabled) {
      addError("PROVIDER_DISABLED", `${prefix}.enabled`, `${providerName} is selected but disabled.`);
      continue;
    }
    if (providerName === "fixture") continue;

    if (!config.policy.respectRobotsTxt && !provider.authorization.robotsExceptionGranted) {
      addError(
        "ROBOTS_POLICY_REQUIRED",
        "policy.respectRobotsTxt",
        `${providerName} requires robots.txt enforcement unless a written exception is approved.`
      );
    }
    if (provider.authorization.status !== "approved") {
      addError(
        "AUTHORIZATION_UNVERIFIED",
        `${prefix}.authorization.status`,
        `${providerName} authorization has not been verified.`
      );
    }
    if (!provider.authorization.evidence) {
      addError(
        "AUTHORIZATION_EVIDENCE_REQUIRED",
        `${prefix}.authorization.evidence`,
        `${providerName} requires written authorization evidence.`
      );
    }
    if (!provider.authorization.reviewedAt) {
      addError(
        "AUTHORIZATION_REVIEW_REQUIRED",
        `${prefix}.authorization.reviewedAt`,
        `${providerName} authorization must have a review timestamp.`
      );
    } else {
      const reviewedAt = new Date(provider.authorization.reviewedAt);
      const age = now.getTime() - reviewedAt.getTime();
      if (age < 0) {
        addError(
          "AUTHORIZATION_REVIEW_IN_FUTURE",
          `${prefix}.authorization.reviewedAt`,
          `${providerName} authorization review cannot be in the future.`
        );
      } else if (age > config.policy.authorizationReviewMaxAgeMs) {
        addError(
          "AUTHORIZATION_REVIEW_STALE",
          `${prefix}.authorization.reviewedAt`,
          `${providerName} authorization review is older than ${formatDuration(config.policy.authorizationReviewMaxAgeMs)}.`
        );
      }
    }
    if (
      provider.authorization.expiresAt &&
      new Date(provider.authorization.expiresAt).getTime() <= now.getTime()
    ) {
      addError(
        "AUTHORIZATION_EXPIRED",
        `${prefix}.authorization.expiresAt`,
        `${providerName} authorization has expired.`
      );
    }
    if (!provider.authorization.expiresAt) {
      addError(
        "AUTHORIZATION_EXPIRY_REQUIRED",
        `${prefix}.authorization.expiresAt`,
        `${providerName} authorization requires an explicit expiry timestamp.`
      );
    }
    if (!hasScope(provider.authorization, "schedule")) {
      addError(
        "SCHEDULE_SCOPE_REQUIRED",
        `${prefix}.authorization.scopes`,
        `${providerName} requires the schedule scope.`
      );
    }
    if (
      providerName === "naver" &&
      config.run.maxDetailGamesPerRun > 0 &&
      (!hasScope(provider.authorization, "relay") ||
        !hasScope(provider.authorization, "record"))
    ) {
      addError(
        "DETAIL_SCOPE_REQUIRED",
        `${prefix}.authorization.scopes`,
        `${providerName} requires both relay and record scopes while detail crawling is enabled.`
      );
    }
    if (
      providerName === "naver" &&
      config.data.includePlayDescriptions &&
      !hasScope(provider.authorization, "relay-text")
    ) {
      addError(
        "RELAY_TEXT_SCOPE_REQUIRED",
        `${prefix}.authorization.scopes`,
        "Play descriptions are relay text and require explicit relay-text authorization."
      );
    }
    if (
      providerName === "kbo" &&
      (!provider.authorization.robotsExceptionGranted ||
        !hasScope(provider.authorization, "robots-disallow-override"))
    ) {
      addError(
        "KBO_ROBOTS_EXCEPTION_REQUIRED",
        `${prefix}.authorization.robotsExceptionGranted`,
        "KBO's known robots block may only be overridden by approved written evidence explicitly scoped to robots-disallow-override."
      );
    }
    if (
      providerName === "naver" &&
      (!provider.authorization.robotsExceptionGranted ||
        !hasScope(provider.authorization, "robots-disallow-override"))
    ) {
      addError(
        "NAVER_ROBOTS_EXCEPTION_REQUIRED",
        `${prefix}.authorization.robotsExceptionGranted`,
        "Naver Sports API host has no verified allowing robots rule; strict operation requires written evidence scoped to robots-disallow-override."
      );
    }
    if (provider.authorization.robotsExceptionGranted) {
      if (
        provider.authorization.status !== "approved" ||
        !provider.authorization.evidence ||
        !hasScope(provider.authorization, "robots-disallow-override")
      ) {
        addError(
          "ROBOTS_EXCEPTION_SCOPE_REQUIRED",
          `${prefix}.authorization.robotsExceptionGranted`,
          `${providerName} robots override requires approved written evidence explicitly scoped to robots-disallow-override.`
        );
      }
    }
    if (provider.kind === "undocumented-api") {
      if (!config.policy.allowUndocumentedApi) {
        addError(
          "UNDOCUMENTED_API_BLOCKED",
          "policy.allowUndocumentedApi",
          `${providerName} uses an undocumented API, but the global policy does not allow it.`
        );
      }
      if (!provider.authorization.undocumentedApiApproved) {
        addError(
          "UNDOCUMENTED_API_APPROVAL_REQUIRED",
          `${prefix}.authorization.undocumentedApiApproved`,
          `${providerName} needs provider-specific written approval for undocumented API use.`
        );
      }
    }
  }

  for (const providerName of ["kbo", "naver"]) {
    const provider = config.providers[providerName];
    if (!requiredProviders.includes(providerName) && provider.enabled) {
      addWarning(
        "UNUSED_PROVIDER_ENABLED",
        `providers.${providerName}.enabled`,
        `${providerName} is enabled but not used by source=${config.source}.`
      );
    }
    if (!provider.enabled && provider.authorization.status === "unverified") {
      addWarning(
        "PROVIDER_DISABLED_UNVERIFIED",
        `providers.${providerName}.authorization.status`,
        `${providerName} remains safely disabled with unverified authorization.`
      );
    }
  }

  return {
    ok: errors.length === 0,
    enforcement: config.policy.enforcement,
    checkedAt: now.toISOString(),
    errors,
    warnings,
  };
}

export const checkCrawlerPolicy = evaluateCrawlerPolicy;

export function assertCrawlerPolicy(config, options) {
  const result = evaluateCrawlerPolicy(config, options);
  if (!result.ok) {
    const message = result.errors
      .map((entry) => `${entry.path}: ${entry.message}`)
      .join("\n");
    throw new ConfigError(
      "POLICY_CHECK_FAILED",
      `Crawler policy check failed:\n${message}`,
      result.errors
    );
  }
  return result;
}

function redactUrlSecrets(value) {
  if (typeof value !== "string" || !/^https?:\/\//i.test(value)) return value;
  try {
    const url = new URL(value);
    if (url.username) url.username = REDACTED;
    if (url.password) url.password = REDACTED;
    for (const key of [...url.searchParams.keys()]) {
      if (/(?:token|secret|password|api[_-]?key|cookie|session|credential)/i.test(key)) {
        url.searchParams.set(key, REDACTED);
      }
    }
    return url.toString();
  } catch {
    return value;
  }
}

export function redactSecrets(value, pathParts = []) {
  if (Array.isArray(value)) {
    return value.map((entry, index) => redactSecrets(entry, [...pathParts, String(index)]));
  }
  if (!isPlainObject(value)) return redactUrlSecrets(value);
  const output = {};
  for (const [key, entry] of Object.entries(value)) {
    const lower = key.toLowerCase();
    const parent = pathParts.at(-1)?.toLowerCase();
    const sensitive =
      /(?:token|secret|password|passphrase|api[_-]?key|cookie|session|credential)/i.test(key) ||
      lower === "evidence" ||
      (lower === "authorization" &&
        (parent === "headers" || !isPlainObject(entry)));
    output[key] = sensitive
      ? entry == null
        ? entry
        : REDACTED
      : redactSecrets(entry, [...pathParts, key]);
  }
  return output;
}

export const redactConfig = redactSecrets;

export function formatConfigForDisplay(config) {
  return JSON.stringify(redactConfig(config), null, 2);
}

export function formatHelp() {
  return `Inning Log crawler

Usage:
  node src/crawler.js [options]

Configuration:
  --config PATH           YAML file (default: config/crawler.yml)
  --profile NAME          YAML profile (default: fixture)
  --env-file PATH         Optional KEY=VALUE file; process environment wins
  --set PATH=VALUE        Override one config leaf; repeatable for unique paths

Run:
  --source SOURCE         fixture | kbo | naver | hybrid
  --date YYYY-MM-DD       Strict target date; omitted means runtime-local today
  --once                  Run once
  --cron[="EXPR"]         Scheduled mode; optional strict 5-field cron
  --scheduled[="EXPR"]    Alias for --cron
  --max-games N           1..20 schedule rows per run
  --max-details N         0..5 detail calls; cannot exceed max-games
  --out PATH              Output directory, resolved inside the crawler project
  --force-write           Write even when the content fingerprint is unchanged
  --dry-run               Disable writes and network execution at orchestration
  --no-write              Disable output writes

Inspection:
  --print-config          Print the effective config with secrets redacted
  --check-config          Validate and exit
  --check-policy          Evaluate strict provider authorization policy and exit
  --help                  Show this help

Precedence (lowest to highest):
  YAML base < selected YAML profile < env-file < process CRAWLER_* env < CLI/--set

Safety defaults:
  The default fixture profile uses local data only. KBO and Naver are disabled,
  authorization is unverified, and undocumented APIs fail closed until explicit
  written approval, scope, review timestamps, and both policy switches are set.

Examples:
  node src/crawler.js --check-config
  node src/crawler.js --profile fixture --date 2026-08-25 --dry-run
  node src/crawler.js --scheduled="*/5 * * * *" --check-policy
  node src/crawler.js --set request.timeoutMs=15s --print-config`;
}
