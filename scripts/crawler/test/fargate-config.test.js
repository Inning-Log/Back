import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import path from "node:path";
import test from "node:test";
import {
  FargateConfigError,
  KBO_ENDPOINTS,
  assertAwsRuntimeConfig,
  evaluateLivePolicy,
  loadFargateConfig,
  parseDuration,
  redactFargateConfig,
} from "../src/fargate/config.js";
import { parseFargateArgs } from "../src/fargate/main.js";

test("Fargate fixture profile is enabled, local-only, and policy-safe", async () => {
  const loaded = await loadFargateConfig({ env: {} });
  assert.equal(loaded.profile, "fixture");
  assert.equal(loaded.config.provider, "fixture");
  assert.equal(evaluateLivePolicy(loaded.config).ok, true);
  assert.equal(loaded.config.endpoints.schedule, KBO_ENDPOINTS.schedule);
  assert.equal(loaded.config.endpoints.scoreboard, KBO_ENDPOINTS.scoreboard);
  assert.equal(loaded.config.polling.finalCheckOffsetsMs[2], 90 * 60_000);
});

test("KBO deployment profile remains fail-closed by default", async () => {
  const loaded = await loadFargateConfig({ profile: "kbo-locked", env: {} });
  const report = evaluateLivePolicy(loaded.config, new Date("2026-08-26T00:00:00Z"));
  assert.equal(report.ok, false);
  assert.ok(report.errors.some((entry) => entry.code === "KILL_SWITCH_ACTIVE"));
  assert.ok(report.errors.some((entry) => entry.code === "ROBOTS_EXCEPTION_REQUIRED"));
  assert.throws(() => assertAwsRuntimeConfig(loaded.config), /AWS runtime settings are missing/);
});

test("reviewed live settings and AWS resource IDs are explicit environment overrides", async () => {
  const loaded = await loadFargateConfig({
    profile: "kbo-locked",
    env: {
      CRAWLER_ENABLED: "true",
      CRAWLER_KILL_SWITCH: "false",
      CRAWLER_OPERATOR_CONTACT: "mailto:operator@school.ac.kr",
      CRAWLER_AUTH_STATUS: "approved",
      CRAWLER_AUTH_EVIDENCE: "KBO-written-approval-2026-08",
      CRAWLER_AUTH_REVIEWED_AT: "2026-08-25T00:00:00+09:00",
      CRAWLER_AUTH_EXPIRES_AT: "2026-09-25T00:00:00+09:00",
      CRAWLER_AUTH_SCOPES: "schedule-page,scoreboard-page,robots-disallow-override",
      CRAWLER_ROBOTS_EXCEPTION_GRANTED: "true",
      CRAWLER_STATE_TABLE: "inning-log-crawler-state",
      CRAWLER_SNAPSHOT_QUEUE_URL: "https://sqs.ap-northeast-2.amazonaws.com/123456789012/inning-log-game-snapshots",
      CRAWLER_SCHEDULER_ROLE_ARN: "arn:aws:iam::123456789012:role/inning-log-crawler-scheduler",
      CRAWLER_SCHEDULER_DLQ_ARN: "arn:aws:sqs:ap-northeast-2:123456789012:inning-log-crawler-scheduler-dlq",
      CRAWLER_ECS_CLUSTER_ARN: "arn:aws:ecs:ap-northeast-2:123456789012:cluster/inning-log-crawler",
      CRAWLER_ECS_TASK_DEFINITION: "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/inning-log-crawler:7",
      CRAWLER_ECS_SUBNET_IDS: "subnet-abc123,subnet-def456",
      CRAWLER_ECS_SECURITY_GROUP_IDS: "sg-abc123",
    },
  });
  assert.equal(
    evaluateLivePolicy(loaded.config, new Date("2026-08-26T00:00:00+09:00")).ok,
    true
  );
  assert.doesNotThrow(() => assertAwsRuntimeConfig(loaded.config));
  assert.deepEqual(loaded.config.aws.ecs.subnetIds, ["subnet-abc123", "subnet-def456"]);
  assert.equal(redactFargateConfig(loaded.config).policy.authorizationEvidence, "[REDACTED]");
});

test("Fargate config and CLI reject unknown or conflicting inputs", async () => {
  await assert.rejects(
    loadFargateConfig({ env: { CRAWLER_NAVER_URL: "https://example.test" } }),
    FargateConfigError
  );
  assert.throws(() => parseFargateArgs(["--plan-day", "--run-game-window"]), FargateConfigError);
  assert.throws(() => parseFargateArgs(["--run-game-window", "--dry-run"]), FargateConfigError);
  assert.throws(() => parseFargateArgs(["--plan-day", "--date", "2026-02-30"]), FargateConfigError);
  assert.equal(parseDuration("1h 30m"), 5_400_000);
});

test("Docker image is locked to the Fargate entrypoint and excludes legacy provider source", async () => {
  const loaded = await loadFargateConfig({ env: {} });
  const dockerfile = await readFile(path.join(loaded.root, "Dockerfile"), "utf8");
  assert.match(dockerfile, /ENTRYPOINT \["node", "src\/fargate\/main\.js"\]/);
  assert.match(dockerfile, /CMD \["--plan-day", "--profile", "fixture", "--dry-run"\]/);
  assert.doesNotMatch(dockerfile, /COPY[^\n]*\bsrc\s+\.\/src/);
  assert.doesNotMatch(dockerfile, /providers\/naver|crawler\.yml/);
});
