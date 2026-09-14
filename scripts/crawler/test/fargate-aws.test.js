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
  assert.equal(body.type, "KBO_GAME_SNAPSHOT");
  assert.match(body.idempotencyKey, /^2026-08-26:game-window:[a-f0-9]{64}$/);

  await publisher.publishIfChanged({
    ...base,
    mode: "schedule-month",
    date: "2026-08-01",
    month: "2026-08",
  });
  assert.equal(JSON.parse(sent[1].MessageBody).type, "KBO_SCHEDULE_MONTH_SNAPSHOT");
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

test("field observation refresh alone does not publish, but A to B to A does", async () => {
  const config = await awsConfig();
  const sent = [];
  const publisher = new SnapshotPublisher(config,new MemoryStateStore(), {
    sqsClient: {send: async command => { sent.push(command.input); return {MessageId:"message"}; }},
  });
  const snapshot = {
    schemaVersion:1,source:"kbo-pages",mode:"game-window",date:"2026-08-26",
    observedAt:"2026-08-26T10:00:00Z",anomalies:[],
    games:[{score:{home:5,away:3},meta:{scheduleObservedAt:"2026-08-26T10:00:00Z",resultObservedAt:"2026-08-26T10:00:00Z",resultSource:"SCOREBOARD"}}],
  };
  await publisher.publishIfChanged(snapshot);
  const refreshed = structuredClone(snapshot);
  refreshed.observedAt = "2026-08-26T10:01:00Z";
  refreshed.games[0].meta.resultObservedAt = refreshed.observedAt;
  refreshed.games[0].meta.scheduleObservedAt = refreshed.observedAt;
  assert.equal((await publisher.publishIfChanged(refreshed)).changed,false);
  refreshed.games[0].score.home = 4;
  assert.equal((await publisher.publishIfChanged(refreshed)).changed,true);
  refreshed.games[0].score.home = 5;
  refreshed.observedAt = "2026-08-26T10:02:00Z";
  refreshed.games[0].meta.resultObservedAt = refreshed.observedAt;
  assert.equal((await publisher.publishIfChanged(refreshed)).changed,true);
  assert.equal(sent.length,3);
});
