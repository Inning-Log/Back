import path from "node:path";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import YAML from "yaml";
import { z } from "zod";

export const FARGATE_ROOT = path.resolve(
  fileURLToPath(new URL("../..", import.meta.url))
);

export const KBO_ENDPOINTS = Object.freeze({
  schedule: "https://www.koreabaseball.com/Schedule/Schedule.aspx",
  scoreboard: "https://www.koreabaseball.com/Schedule/ScoreBoard.aspx",
  scheduleData: "https://www.koreabaseball.com/ws/Schedule.asmx/GetScheduleList",
});

const DURATION_KEYS = new Set([
  "authorizationReviewMaxAgeMs",
  "timeoutMs",
  "minHostIntervalMs",
  "retryBaseDelayMs",
  "schemaRetryDelayMs",
  "planLeadMs",
  "scoreboardLeadMs",
  "scheduleRefreshMs",
  "scoreboardRefreshMs",
  "delayedRefreshMs",
  "leaseRenewMs",
  "hardTimeoutMs",
]);

const BOOLEAN_ENV = new Set([
  "CRAWLER_ENABLED",
  "CRAWLER_KILL_SWITCH",
  "CRAWLER_ROBOTS_EXCEPTION_GRANTED",
  "CRAWLER_ECS_ASSIGN_PUBLIC_IP",
]);

const KNOWN_ENV = new Set([
  "CRAWLER_CONFIG",
  "CRAWLER_PROFILE",
  "CRAWLER_SCHEDULE_SERIES",
  "CRAWLER_ENABLED",
  "CRAWLER_KILL_SWITCH",
  "CRAWLER_OPERATOR_CONTACT",
  "CRAWLER_AUTH_STATUS",
  "CRAWLER_AUTH_EVIDENCE",
  "CRAWLER_AUTH_REVIEWED_AT",
  "CRAWLER_AUTH_EXPIRES_AT",
  "CRAWLER_AUTH_SCOPES",
  "CRAWLER_ROBOTS_EXCEPTION_GRANTED",
  "CRAWLER_STATE_TABLE",
  "CRAWLER_SNAPSHOT_QUEUE_URL",
  "CRAWLER_SCHEDULER_GROUP",
  "CRAWLER_SCHEDULER_ROLE_ARN",
  "CRAWLER_SCHEDULER_DLQ_ARN",
  "CRAWLER_ECS_CLUSTER_ARN",
  "CRAWLER_ECS_TASK_DEFINITION",
  "CRAWLER_ECS_CONTAINER_NAME",
  "CRAWLER_ECS_SUBNET_IDS",
  "CRAWLER_ECS_SECURITY_GROUP_IDS",
  "CRAWLER_ECS_ASSIGN_PUBLIC_IP",
  "CRAWLER_PLAN_LEAD_MINUTES",
  "CRAWLER_SCOREBOARD_LEAD_MINUTES",
  "CRAWLER_SCHEDULE_REFRESH_MINUTES",
  "CRAWLER_SCOREBOARD_REFRESH_MINUTES",
  "CRAWLER_DELAYED_REFRESH_MINUTES",
  "CRAWLER_LEASE_RENEW_MINUTES",
  "CRAWLER_FINAL_CHECK_MINUTES",
  "CRAWLER_HARD_TIMEOUT_MINUTES",
  "CRAWLER_MAX_LOGICAL_REQUESTS_PER_HOUR",
  "CRAWLER_MAX_ATTEMPTS_PER_HOUR",
  "CRAWLER_SCHEMA_RETRY_COUNT",
  "CRAWLER_SCHEMA_RETRY_MINUTES",
]);

const nullableString = z.string().min(1).nullable();
const arn = z.string().regex(/^arn:aws[a-z-]*:/).nullable();

const configSchema = z.object({
  enabled: z.boolean(),
  provider: z.enum(["fixture", "kbo"]),
  timezone: z.literal("Asia/Seoul"),
  runtime: z.object({
    persistence: z.enum(["memory", "aws"]),
  }).strict(),
  scheduleTransport: z.enum(["html", "page-ajax"]).default("html"),
  scheduleSeries: z.enum(["0,9,6", "1", "3,4,5,7"]).default("0,9,6"),
  identity: z.object({
    product: z.string().regex(/^[A-Za-z][A-Za-z0-9_-]{2,63}$/),
    version: z.string().min(1).max(32),
    contact: z.string().min(1).max(200),
  }).strict(),
  policy: z.object({
    accessMode: z.enum(["written-authorization", "operator-requested"]).default("written-authorization"),
    killSwitch: z.boolean(),
    authorizationStatus: z.enum(["unverified", "approved", "revoked"]),
    authorizationEvidence: nullableString,
    authorizationReviewedAt: nullableString,
    authorizationExpiresAt: nullableString,
    authorizationScopes: z.array(z.string().min(1)).max(8),
    robotsExceptionGranted: z.boolean(),
    authorizationReviewMaxAgeMs: z.number().int().min(60_000).max(31 * 86_400_000),
  }).strict(),
  endpoints: z.object({
    schedule: z.literal(KBO_ENDPOINTS.schedule),
    scoreboard: z.literal(KBO_ENDPOINTS.scoreboard),
  }).strict(),
  fixture: z.object({
    scheduleFile: z.string().min(1),
    scoreboardFile: z.string().min(1),
  }).strict(),
  request: z.object({
    timeoutMs: z.number().int().min(1_000).max(30_000),
    minHostIntervalMs: z.number().int().min(2_000).max(60_000),
    maxResponseBytes: z.number().int().min(64_000).max(2_097_152),
    retryCount: z.number().int().min(0).max(2),
    schemaRetryCount: z.number().int().min(0).max(2).default(2),
    schemaRetryDelayMs: z.number().int().min(120_000).max(300_000).default(120_000),
    retryBaseDelayMs: z.number().int().min(500).max(10_000),
    maxLogicalRequestsPerHour: z.number().int().min(2).max(120),
    maxAttemptsPerHour: z.number().int().min(2).max(180),
  }).strict().superRefine((value, ctx) => {
    if (value.maxAttemptsPerHour < value.maxLogicalRequestsPerHour) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["maxAttemptsPerHour"],
        message: "attempt quota must be greater than or equal to logical request quota",
      });
    }
  }),
  polling: z.object({
    planLeadMs: z.number().int().min(0).max(6 * 3_600_000),
    scoreboardLeadMs: z.number().int().min(0).max(2 * 3_600_000),
    scheduleRefreshMs: z.number().int().min(5 * 60_000).max(60 * 60_000),
    scoreboardRefreshMs: z.number().int().min(2 * 60_000).max(15 * 60_000),
    delayedRefreshMs: z.number().int().min(2 * 60_000).max(30 * 60_000),
    leaseRenewMs: z.number().int().min(60_000).max(30 * 60_000),
    finalCheckOffsetsMs: z.array(z.number().int().positive()).length(3),
    hardTimeoutMs: z.number().int().min(30 * 60_000).max(12 * 3_600_000),
  }).strict().superRefine((value, ctx) => {
    if (value.scoreboardLeadMs > value.planLeadMs) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["scoreboardLeadMs"],
        message: "scoreboard lead cannot exceed task plan lead",
      });
    }
    const sorted = [...value.finalCheckOffsetsMs].sort((a, b) => a - b);
    if (!value.finalCheckOffsetsMs.every((entry, index) => entry === sorted[index])) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["finalCheckOffsetsMs"],
        message: "final check offsets must be strictly ascending",
      });
    }
    if (value.finalCheckOffsetsMs.at(-1) >= value.hardTimeoutMs) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        path: ["finalCheckOffsetsMs"],
        message: "last final check must occur before hard timeout",
      });
    }
  }),
  aws: z.object({
    region: z.string().regex(/^[a-z]{2}(?:-gov)?-[a-z]+-\d$/),
    stateTable: nullableString,
    snapshotQueueUrl: z.string().url().nullable(),
    schedulerGroup: z.string().regex(/^[0-9A-Za-z_.-]{1,64}$/),
    schedulerRoleArn: arn,
    schedulerDlqArn: arn,
    ecs: z.object({
      clusterArn: arn,
      taskDefinitionArn: z.string().min(1).nullable(),
      containerName: z.string().regex(/^[A-Za-z0-9_-]{1,255}$/),
      subnetIds: z.array(z.string().regex(/^subnet-[0-9a-f]+$/i)).min(0).max(16),
      securityGroupIds: z.array(z.string().regex(/^sg-[0-9a-f]+$/i)).min(0).max(5),
      assignPublicIp: z.boolean(),
    }).strict(),
  }).strict(),
}).strict();

export class FargateConfigError extends Error {
  constructor(message, details = []) {
    super(message);
    this.name = "FargateConfigError";
    this.code = "FARGATE_CONFIG_INVALID";
    this.details = details;
  }
}

export function parseDuration(value) {
  if (Number.isSafeInteger(value) && value >= 0) return value;
  if (typeof value !== "string" || !value.trim()) {
    throw new FargateConfigError(`Invalid duration: ${String(value)}`);
  }
  const source = value.trim();
  const pattern = /(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)/gi;
  const units = { ms: 1, s: 1_000, m: 60_000, h: 3_600_000, d: 86_400_000 };
  let total = 0;
  let consumed = "";
  for (const match of source.matchAll(pattern)) {
    consumed += match[0].replace(/\s+/g, "");
    total += Number(match[1]) * units[match[2].toLowerCase()];
  }
  if (consumed.toLowerCase() !== source.replace(/\s+/g, "").toLowerCase()) {
    throw new FargateConfigError(`Invalid duration: ${source}`);
  }
  if (!Number.isSafeInteger(total) || total < 0) {
    throw new FargateConfigError(`Duration is out of range: ${source}`);
  }
  return total;
}

function isPlainObject(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function deepMerge(base, override) {
  if (!isPlainObject(base) || !isPlainObject(override)) {
    return structuredClone(override);
  }
  const output = structuredClone(base);
  for (const [key, value] of Object.entries(override)) {
    if (["__proto__", "constructor", "prototype"].includes(key)) {
      throw new FargateConfigError(`Unsafe config key: ${key}`);
    }
    output[key] = isPlainObject(value) && isPlainObject(output[key])
      ? deepMerge(output[key], value)
      : structuredClone(value);
  }
  return output;
}

function parseBoolean(name, value) {
  if (typeof value === "boolean") return value;
  const normalized = String(value).trim().toLowerCase();
  if (normalized === "true" || normalized === "1") return true;
  if (normalized === "false" || normalized === "0") return false;
  throw new FargateConfigError(`${name} must be true or false`);
}

function list(value) {
  return String(value ?? "")
    .split(",")
    .map((entry) => entry.trim())
    .filter(Boolean)
    .filter((entry, index, values) => values.indexOf(entry) === index);
}

function minutes(name, value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) {
    throw new FargateConfigError(`${name} must be a non-negative number of minutes`);
  }
  const result = number * 60_000;
  if (!Number.isSafeInteger(result)) {
    throw new FargateConfigError(`${name} is out of range`);
  }
  return result;
}

function applyEnvironment(config, env) {
  const unknown = Object.keys(env)
    .filter((name) => name.startsWith("CRAWLER_"))
    .filter((name) => !KNOWN_ENV.has(name));
  if (unknown.length > 0) {
    throw new FargateConfigError(`Unknown crawler environment variables: ${unknown.sort().join(", ")}`);
  }
  for (const name of BOOLEAN_ENV) {
    if (env[name] != null) parseBoolean(name, env[name]);
  }

  const mappings = {
    CRAWLER_SCHEDULE_SERIES: ["scheduleSeries", String],
    CRAWLER_ENABLED: ["enabled", (value) => parseBoolean("CRAWLER_ENABLED", value)],
    CRAWLER_KILL_SWITCH: ["policy.killSwitch", (value) => parseBoolean("CRAWLER_KILL_SWITCH", value)],
    CRAWLER_OPERATOR_CONTACT: ["identity.contact", String],
    CRAWLER_AUTH_STATUS: ["policy.authorizationStatus", String],
    CRAWLER_AUTH_EVIDENCE: ["policy.authorizationEvidence", String],
    CRAWLER_AUTH_REVIEWED_AT: ["policy.authorizationReviewedAt", String],
    CRAWLER_AUTH_EXPIRES_AT: ["policy.authorizationExpiresAt", String],
    CRAWLER_AUTH_SCOPES: ["policy.authorizationScopes", list],
    CRAWLER_ROBOTS_EXCEPTION_GRANTED: ["policy.robotsExceptionGranted", (value) => parseBoolean("CRAWLER_ROBOTS_EXCEPTION_GRANTED", value)],
    CRAWLER_STATE_TABLE: ["aws.stateTable", String],
    CRAWLER_SNAPSHOT_QUEUE_URL: ["aws.snapshotQueueUrl", String],
    CRAWLER_SCHEDULER_GROUP: ["aws.schedulerGroup", String],
    CRAWLER_SCHEDULER_ROLE_ARN: ["aws.schedulerRoleArn", String],
    CRAWLER_SCHEDULER_DLQ_ARN: ["aws.schedulerDlqArn", String],
    CRAWLER_ECS_CLUSTER_ARN: ["aws.ecs.clusterArn", String],
    CRAWLER_ECS_TASK_DEFINITION: ["aws.ecs.taskDefinitionArn", String],
    CRAWLER_ECS_CONTAINER_NAME: ["aws.ecs.containerName", String],
    CRAWLER_ECS_SUBNET_IDS: ["aws.ecs.subnetIds", list],
    CRAWLER_ECS_SECURITY_GROUP_IDS: ["aws.ecs.securityGroupIds", list],
    CRAWLER_ECS_ASSIGN_PUBLIC_IP: ["aws.ecs.assignPublicIp", (value) => parseBoolean("CRAWLER_ECS_ASSIGN_PUBLIC_IP", value)],
    CRAWLER_PLAN_LEAD_MINUTES: ["polling.planLeadMs", (value) => minutes("CRAWLER_PLAN_LEAD_MINUTES", value)],
    CRAWLER_SCOREBOARD_LEAD_MINUTES: ["polling.scoreboardLeadMs", (value) => minutes("CRAWLER_SCOREBOARD_LEAD_MINUTES", value)],
    CRAWLER_SCHEDULE_REFRESH_MINUTES: ["polling.scheduleRefreshMs", (value) => minutes("CRAWLER_SCHEDULE_REFRESH_MINUTES", value)],
    CRAWLER_SCOREBOARD_REFRESH_MINUTES: ["polling.scoreboardRefreshMs", (value) => minutes("CRAWLER_SCOREBOARD_REFRESH_MINUTES", value)],
    CRAWLER_DELAYED_REFRESH_MINUTES: ["polling.delayedRefreshMs", (value) => minutes("CRAWLER_DELAYED_REFRESH_MINUTES", value)],
    CRAWLER_LEASE_RENEW_MINUTES: ["polling.leaseRenewMs", (value) => minutes("CRAWLER_LEASE_RENEW_MINUTES", value)],
    CRAWLER_FINAL_CHECK_MINUTES: ["polling.finalCheckOffsetsMs", (value) => list(value).map((entry) => minutes("CRAWLER_FINAL_CHECK_MINUTES", entry))],
    CRAWLER_HARD_TIMEOUT_MINUTES: ["polling.hardTimeoutMs", (value) => minutes("CRAWLER_HARD_TIMEOUT_MINUTES", value)],
    CRAWLER_MAX_LOGICAL_REQUESTS_PER_HOUR: ["request.maxLogicalRequestsPerHour", Number],
    CRAWLER_MAX_ATTEMPTS_PER_HOUR: ["request.maxAttemptsPerHour", Number],
    CRAWLER_SCHEMA_RETRY_COUNT: ["request.schemaRetryCount", Number],
    CRAWLER_SCHEMA_RETRY_MINUTES: ["request.schemaRetryDelayMs", (value) => minutes("CRAWLER_SCHEMA_RETRY_MINUTES", value)],
  };

  const output = structuredClone(config);
  for (const [name, [target, convert]] of Object.entries(mappings)) {
    if (env[name] == null || env[name] === "") continue;
    let cursor = output;
    const parts = target.split(".");
    for (const part of parts.slice(0, -1)) cursor = cursor[part];
    cursor[parts.at(-1)] = convert(env[name]);
  }
  if (env.AWS_REGION) output.aws.region = env.AWS_REGION;
  return output;
}

function normalizeDurations(value, key = null) {
  if (Array.isArray(value)) {
    return value.map((entry) => key === "finalCheckOffsetsMs" ? parseDuration(entry) : normalizeDurations(entry));
  }
  if (!isPlainObject(value)) return key && DURATION_KEYS.has(key) ? parseDuration(value) : value;
  return Object.fromEntries(
    Object.entries(value).map(([childKey, childValue]) => [
      childKey,
      normalizeDurations(childValue, childKey),
    ])
  );
}

function resolveFixturePaths(config, root) {
  const output = structuredClone(config);
  for (const key of ["scheduleFile", "scoreboardFile"]) {
    const resolved = path.resolve(root, output.fixture[key]);
    const relative = path.relative(root, resolved);
    if (relative.startsWith("..") || path.isAbsolute(relative)) {
      throw new FargateConfigError(`fixture.${key} must stay inside crawler root`);
    }
    output.fixture[key] = resolved;
  }
  return output;
}

export function validateFargateConfig(candidate) {
  const result = configSchema.safeParse(candidate);
  if (!result.success) {
    const details = result.error.issues.map((issue) => ({
      path: issue.path.join("."),
      message: issue.message,
    }));
    throw new FargateConfigError(
      `Fargate crawler config validation failed (${details.length} issue${details.length === 1 ? "" : "s"})`,
      details
    );
  }
  return result.data;
}

export async function loadFargateConfig(options = {}) {
  const env = options.env ?? process.env;
  const root = path.resolve(options.root ?? FARGATE_ROOT);
  const configPath = path.resolve(root, options.configPath ?? env.CRAWLER_CONFIG ?? "config/fargate.yml");
  const raw = YAML.parse(await readFile(configPath, "utf8"), { uniqueKeys: true });
  if (!isPlainObject(raw) || raw.version !== 1 || !isPlainObject(raw.base) || !isPlainObject(raw.profiles)) {
    throw new FargateConfigError("fargate.yml must contain version: 1, base, and profiles");
  }
  const profile = options.profile ?? env.CRAWLER_PROFILE ?? raw.defaultProfile;
  if (!Object.hasOwn(raw.profiles, profile)) {
    throw new FargateConfigError(`Unknown Fargate profile: ${String(profile)}`);
  }
  let config = deepMerge(raw.base, raw.profiles[profile]);
  config = normalizeDurations(config);
  config = applyEnvironment(config, env);
  config = resolveFixturePaths(config, root);
  return {
    config: validateFargateConfig(config),
    profile,
    configPath,
    root,
  };
}

function validInstant(value) {
  return typeof value === "string" && Number.isFinite(Date.parse(value));
}

function validOperatorContact(value) {
  const text = String(value ?? "").trim();
  if (/^mailto:[^\s@]+@[^\s@]+\.[^\s@]+$/i.test(text)) return true;
  try {
    const url = new URL(text);
    return url.protocol === "https:" && Boolean(url.hostname);
  } catch {
    return false;
  }
}

export function evaluateLivePolicy(config, now = new Date()) {
  const errors = [];
  const add = (code, message) => errors.push({ code, message });
  if (config.provider === "fixture") {
    if (!config.enabled) add("CRAWLER_DISABLED", "fixture crawler is disabled");
    if (config.policy.killSwitch) add("KILL_SWITCH_ACTIVE", "fixture kill switch is active");
    return { ok: errors.length === 0, errors };
  }

  if (!config.enabled) add("CRAWLER_DISABLED", "crawler is disabled");
  if (config.policy.killSwitch) add("KILL_SWITCH_ACTIVE", "external crawler kill switch is active");
  // An operator-requested run is not evidence of third-party permission.
  // Keep its opt-in, contact and endpoint checks without inventing approval metadata.
  if (config.policy.accessMode === "operator-requested") {
    if (config.policy.authorizationStatus === "revoked") add("AUTHORIZATION_REVOKED", "access was explicitly revoked");
    if (!validOperatorContact(config.identity.contact) || /\.invalid\b|example\.(?:com|org|net)\b/i.test(config.identity.contact)) {
      add("OPERATOR_CONTACT_PLACEHOLDER", "operator contact must be reachable before live use");
    }
    if (config.endpoints.schedule !== KBO_ENDPOINTS.schedule || config.endpoints.scoreboard !== KBO_ENDPOINTS.scoreboard) {
      add("ENDPOINT_SCOPE_CHANGED", "KBO page endpoints changed");
    }
    return { ok: errors.length === 0, errors };
  }
  if (config.policy.authorizationStatus !== "approved") {
    add("AUTHORIZATION_NOT_APPROVED", "written authorization is not approved");
  }
  if (!config.policy.authorizationEvidence || /placeholder|example|todo/i.test(config.policy.authorizationEvidence)) {
    add("AUTHORIZATION_EVIDENCE_MISSING", "reviewable authorization evidence is missing");
  }
  const requiredScopes = ["schedule-page", "scoreboard-page", "robots-disallow-override"];
  for (const scope of requiredScopes) {
    if (!config.policy.authorizationScopes.includes(scope)) {
      add("AUTHORIZATION_SCOPE_MISSING", `authorization scope is missing: ${scope}`);
    }
  }
  if (!config.policy.robotsExceptionGranted) {
    add("ROBOTS_EXCEPTION_REQUIRED", "the recorded KBO robots disallow requires an explicit exception");
  }
  if (!validInstant(config.policy.authorizationReviewedAt)) {
    add("AUTHORIZATION_REVIEW_INVALID", "authorizationReviewedAt must be an ISO timestamp");
  } else {
    const reviewedAt = Date.parse(config.policy.authorizationReviewedAt);
    if (reviewedAt > now.getTime() + 5 * 60_000) {
      add("AUTHORIZATION_REVIEW_IN_FUTURE", "authorization review timestamp is unexpectedly in the future");
    } else if (now.getTime() - reviewedAt > config.policy.authorizationReviewMaxAgeMs) {
      add("AUTHORIZATION_REVIEW_STALE", "authorization review is older than the configured maximum age");
    }
  }
  if (!validInstant(config.policy.authorizationExpiresAt)) {
    add("AUTHORIZATION_EXPIRY_INVALID", "authorizationExpiresAt must be an ISO timestamp");
  } else if (Date.parse(config.policy.authorizationExpiresAt) <= now.getTime()) {
    add("AUTHORIZATION_EXPIRED", "authorization has expired");
  } else if (
    validInstant(config.policy.authorizationReviewedAt)
    && Date.parse(config.policy.authorizationExpiresAt) <= Date.parse(config.policy.authorizationReviewedAt)
  ) {
    add("AUTHORIZATION_WINDOW_INVALID", "authorization expiry must be after its review timestamp");
  }
  if (
    !validOperatorContact(config.identity.contact)
    || /\.invalid\b|example\.(?:com|org|net)\b/i.test(config.identity.contact)
  ) {
    add("OPERATOR_CONTACT_PLACEHOLDER", "operator contact must be reachable before live use");
  }
  if (config.endpoints.schedule !== KBO_ENDPOINTS.schedule || config.endpoints.scoreboard !== KBO_ENDPOINTS.scoreboard) {
    add("ENDPOINT_SCOPE_CHANGED", "only the two reviewed KBO page URLs are allowed");
  }
  return { ok: errors.length === 0, errors };
}

export function assertLivePolicy(config, now = new Date()) {
  const report = evaluateLivePolicy(config, now);
  if (!report.ok) {
    const error = new FargateConfigError("Fargate crawler policy check failed", report.errors);
    error.code = "FARGATE_POLICY_BLOCKED";
    throw error;
  }
  return report;
}

export function assertAwsRuntimeConfig(config) {
  const missing = [];
  const require = (pathLabel, value) => {
    if (value == null || value === "" || (Array.isArray(value) && value.length === 0)) missing.push(pathLabel);
  };
  require("aws.stateTable", config.aws.stateTable);
  require("aws.snapshotQueueUrl", config.aws.snapshotQueueUrl);
  require("aws.schedulerRoleArn", config.aws.schedulerRoleArn);
  require("aws.schedulerDlqArn", config.aws.schedulerDlqArn);
  require("aws.ecs.clusterArn", config.aws.ecs.clusterArn);
  require("aws.ecs.taskDefinitionArn", config.aws.ecs.taskDefinitionArn);
  require("aws.ecs.subnetIds", config.aws.ecs.subnetIds);
  require("aws.ecs.securityGroupIds", config.aws.ecs.securityGroupIds);
  if (missing.length > 0) {
    throw new FargateConfigError(`AWS runtime settings are missing: ${missing.join(", ")}`);
  }
}

export function redactFargateConfig(config) {
  const output = structuredClone(config);
  if (output.policy.authorizationEvidence) output.policy.authorizationEvidence = "[REDACTED]";
  if (output.aws.snapshotQueueUrl) {
    try {
      const url = new URL(output.aws.snapshotQueueUrl);
      output.aws.snapshotQueueUrl = `${url.origin}/[REDACTED]`;
    } catch {
      output.aws.snapshotQueueUrl = "[REDACTED]";
    }
  }
  return output;
}
