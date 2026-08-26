import assert from "node:assert/strict";
import test from "node:test";
import {
  EcsScheduleManager,
  MemoryStateStore,
  SnapshotPublisher,
  kstLocalTimestamp,
} from "../src/fargate/aws.js";
import { loadFargateConfig } from "../src/fargate/config.js";

async function awsConfig() {
  const { config } = await loadFargateConfig({ env: {} });
  config.aws.stateTable = "state";
  config.aws.snapshotQueueUrl = "https://sqs.ap-northeast-2.amazonaws.com/123456789012/snapshots";
  config.aws.schedulerRoleArn = "arn:aws:iam::123456789012:role/scheduler";
  config.aws.schedulerDlqArn = "arn:aws:sqs:ap-northeast-2:123456789012:dlq";
  config.aws.ecs.clusterArn = "arn:aws:ecs:ap-northeast-2:123456789012:cluster/crawler";
  config.aws.ecs.taskDefinitionArn = "arn:aws:ecs:ap-northeast-2:123456789012:task-definition/crawler:4";
  config.aws.ecs.subnetIds = ["subnet-abc123"];
  config.aws.ecs.securityGroupIds = ["sg-abc123"];
  return config;
}

test("SQS publisher emits one idempotent message for unchanged content", async () => {
  const config = await awsConfig();
  const state = new MemoryStateStore();
  const sent = [];
  const publisher = new SnapshotPublisher(config, state, {
    now: () => Date.parse("2026-08-26T10:00:00Z"),
    sqsClient: {
      send: async (command) => {
        sent.push(command.input);
        return { MessageId: "m-1" };
      },
    },
  });
  const base = {
    schemaVersion: 1,
    source: "kbo-pages",
    mode: "game-window",
    date: "2026-08-26",
    observedAt: "2026-08-26T10:00:00Z",
    games: [],
    anomalies: [],
    requestMetrics: { logicalRequests: 1, attempts: 1 },
  };
  assert.equal((await publisher.publishIfChanged(base)).changed, true);
  assert.equal((await publisher.publishIfChanged({
    ...base,
    observedAt: "2026-08-26T10:02:00Z",
    requestMetrics: { logicalRequests: 2, attempts: 2 },
  })).changed, false);
  assert.equal(sent.length, 1);
  const body = JSON.parse(sent[0].MessageBody);
  assert.match(body.idempotencyKey, /^2026-08-26:game-window:[a-f0-9]{64}$/);
});

test("EventBridge manager creates a one-time ECS task with safe fixed parameters", async () => {
  const config = await awsConfig();
  const commands = [];
  const manager = new EcsScheduleManager(config, "kbo-locked", {
    schedulerClient: {
      send: async (command) => {
        commands.push(command);
        if (command.constructor.name === "GetScheduleCommand") {
          throw Object.assign(new Error("missing"), { name: "ResourceNotFoundException" });
        }
        return {};
      },
    },
  });
  const result = await manager.upsert("2026-08-26", "2026-08-26T08:30:00.000Z");
  assert.equal(result.action, "created");
  assert.deepEqual(commands.map((entry) => entry.constructor.name), ["GetScheduleCommand", "CreateScheduleCommand"]);
  const input = commands[1].input;
  assert.equal(input.ScheduleExpression, "at(2026-08-26T17:30:00)");
  assert.equal(input.ScheduleExpressionTimezone, "Asia/Seoul");
  assert.equal(input.ActionAfterCompletion, "DELETE");
  assert.equal(input.Target.EcsParameters.LaunchType, "FARGATE");
  assert.equal(input.Target.EcsParameters.NetworkConfiguration.awsvpcConfiguration.AssignPublicIp, "ENABLED");
  assert.deepEqual(JSON.parse(input.Target.Input).containerOverrides[0].command, [
    "--run-game-window", "--profile", "kbo-locked", "--date", "2026-08-26",
  ]);
  assert.equal(kstLocalTimestamp("2026-08-26T08:30:00Z"), "2026-08-26T17:30:00");

  const desired = manager.request("2026-08-26", "2026-08-26T08:30:00.000Z");
  const unchangedCommands = [];
  const unchanged = new EcsScheduleManager(config, "kbo-locked", {
    schedulerClient: {
      send: async (command) => {
        unchangedCommands.push(command);
        return { ...desired, Arn: "arn:aws:scheduler:ap-northeast-2:123456789012:schedule/ignored" };
      },
    },
  });
  assert.equal((await unchanged.upsert("2026-08-26", "2026-08-26T08:30:00.000Z")).action, "unchanged");
  assert.deepEqual(unchangedCommands.map((entry) => entry.constructor.name), ["GetScheduleCommand"]);
});
