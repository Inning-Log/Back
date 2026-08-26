import {
  ConditionalCheckFailedException,
  DynamoDBClient,
} from "@aws-sdk/client-dynamodb";
import {
  DeleteCommand,
  DynamoDBDocumentClient,
  GetCommand,
  PutCommand,
  UpdateCommand,
} from "@aws-sdk/lib-dynamodb";
import { SendMessageCommand, SQSClient } from "@aws-sdk/client-sqs";
import {
  ConflictException,
  CreateScheduleCommand,
  DeleteScheduleCommand,
  GetScheduleCommand,
  ResourceNotFoundException,
  SchedulerClient,
  UpdateScheduleCommand,
} from "@aws-sdk/client-scheduler";
import { contentFingerprint, stableValue } from "../storage.js";

export class PersistentQuotaError extends Error {
  constructor(message, bucket) {
    super(message);
    this.name = "PersistentQuotaError";
    this.code = "PERSISTENT_REQUEST_QUOTA_EXHAUSTED";
    this.bucket = bucket;
  }
}

function isConditionalFailure(error) {
  return error instanceof ConditionalCheckFailedException
    || error?.name === "ConditionalCheckFailedException";
}

function isNotFound(error) {
  return error instanceof ResourceNotFoundException
    || error?.name === "ResourceNotFoundException";
}

export class MemoryStateStore {
  constructor() {
    this.items = new Map();
    this.quotas = new Map();
  }

  async acquireLease(key, owner, expiresAt, nowSeconds) {
    const current = this.items.get(key);
    if (current && current.expiresAt >= nowSeconds) return false;
    this.items.set(key, { owner, expiresAt, type: "lease" });
    return true;
  }

  async renewLease(key, owner, expiresAt, nowSeconds) {
    const current = this.items.get(key);
    if (!current || current.owner !== owner || current.expiresAt < nowSeconds) return false;
    this.items.set(key, { ...current, expiresAt });
    return true;
  }

  async releaseLease(key, owner) {
    const current = this.items.get(key);
    if (current?.owner === owner) this.items.delete(key);
  }

  async getJson(key) {
    return structuredClone(this.items.get(key)?.payload ?? null);
  }

  async putJson(key, payload, expiresAt = null) {
    this.items.set(key, { type: "json", payload: structuredClone(payload), expiresAt });
  }

  async reserveQuota(bucket, limit) {
    const next = (this.quotas.get(bucket) ?? 0) + 1;
    if (next > limit) throw new PersistentQuotaError(`Persistent request quota exceeded for ${bucket}`, bucket);
    this.quotas.set(bucket, next);
    return next;
  }
}

export class DynamoStateStore {
  constructor(config, dependencies = {}) {
    this.tableName = config.aws.stateTable;
    const lowLevel = dependencies.dynamoClient ?? new DynamoDBClient({ region: config.aws.region });
    this.client = dependencies.documentClient ?? DynamoDBDocumentClient.from(lowLevel, {
      marshallOptions: { removeUndefinedValues: true },
    });
    this.now = dependencies.now ?? (() => Date.now());
  }

  async acquireLease(key, owner, expiresAt, nowSeconds) {
    try {
      await this.client.send(new PutCommand({
        TableName: this.tableName,
        Item: {
          pk: key,
          type: "lease",
          owner,
          expiresAt,
          updatedAt: new Date(this.now()).toISOString(),
        },
        ConditionExpression: "attribute_not_exists(pk) OR expiresAt < :now",
        ExpressionAttributeValues: { ":now": nowSeconds },
      }));
      return true;
    } catch (error) {
      if (isConditionalFailure(error)) return false;
      throw error;
    }
  }

  async renewLease(key, owner, expiresAt, nowSeconds) {
    try {
      await this.client.send(new UpdateCommand({
        TableName: this.tableName,
        Key: { pk: key },
        UpdateExpression: "SET expiresAt = :expiresAt, updatedAt = :updatedAt",
        ConditionExpression: "#owner = :owner AND expiresAt >= :now",
        ExpressionAttributeNames: { "#owner": "owner" },
        ExpressionAttributeValues: {
          ":owner": owner,
          ":expiresAt": expiresAt,
          ":now": nowSeconds,
          ":updatedAt": new Date(this.now()).toISOString(),
        },
      }));
      return true;
    } catch (error) {
      if (isConditionalFailure(error)) return false;
      throw error;
    }
  }

  async releaseLease(key, owner) {
    try {
      await this.client.send(new DeleteCommand({
        TableName: this.tableName,
        Key: { pk: key },
        ConditionExpression: "#owner = :owner",
        ExpressionAttributeNames: { "#owner": "owner" },
        ExpressionAttributeValues: { ":owner": owner },
      }));
    } catch (error) {
      if (!isConditionalFailure(error)) throw error;
    }
  }

  async getJson(key) {
    const response = await this.client.send(new GetCommand({
      TableName: this.tableName,
      Key: { pk: key },
      ConsistentRead: true,
    }));
    return response.Item?.payload == null ? null : structuredClone(response.Item.payload);
  }

  async putJson(key, payload, expiresAt = null) {
    const size = Buffer.byteLength(JSON.stringify(payload), "utf8");
    if (size > 350_000) throw new Error(`DynamoDB JSON payload exceeds the 350KB application limit (${size} bytes)`);
    await this.client.send(new PutCommand({
      TableName: this.tableName,
      Item: {
        pk: key,
        type: "json",
        payload,
        expiresAt: expiresAt ?? undefined,
        updatedAt: new Date(this.now()).toISOString(),
      },
    }));
  }

  async reserveQuota(bucket, limit, expiresAt) {
    try {
      const response = await this.client.send(new UpdateCommand({
        TableName: this.tableName,
        Key: { pk: `QUOTA#${bucket}` },
        UpdateExpression: "SET expiresAt = :expiresAt, updatedAt = :updatedAt ADD #count :one",
        ConditionExpression: "attribute_not_exists(#count) OR #count < :limit",
        ExpressionAttributeNames: { "#count": "count" },
        ExpressionAttributeValues: {
          ":one": 1,
          ":limit": limit,
          ":expiresAt": expiresAt,
          ":updatedAt": new Date(this.now()).toISOString(),
        },
        ReturnValues: "UPDATED_NEW",
      }));
      return response.Attributes?.count ?? null;
    } catch (error) {
      if (isConditionalFailure(error)) {
        throw new PersistentQuotaError(`Persistent request quota exceeded for ${bucket}`, bucket);
      }
      throw error;
    }
  }
}

function snapshotFingerprint(snapshot) {
  const content = structuredClone(snapshot);
  delete content.observedAt;
  delete content.requestMetrics;
  delete content.contentFingerprint;
  return contentFingerprint(stableValue(content));
}

export class SnapshotPublisher {
  constructor(config, state, dependencies = {}) {
    this.queueUrl = config.aws.snapshotQueueUrl;
    this.state = state;
    this.client = dependencies.sqsClient ?? new SQSClient({ region: config.aws.region });
    this.now = dependencies.now ?? (() => Date.now());
  }

  async publishIfChanged(snapshot) {
    const fingerprint = snapshotFingerprint(snapshot);
    const stateKey = `PUBLISHED#${snapshot.date}#${snapshot.mode}`;
    const previous = await this.state.getJson(stateKey);
    if (previous?.fingerprint === fingerprint) {
      return { changed: false, fingerprint, idempotencyKey: previous.idempotencyKey };
    }
    const idempotencyKey = `${snapshot.date}:${snapshot.mode}:${fingerprint}`;
    const message = {
      schemaVersion: 1,
      type: "KBO_GAME_SNAPSHOT",
      idempotencyKey,
      occurredAt: new Date(this.now()).toISOString(),
      payload: { ...snapshot, contentFingerprint: fingerprint },
    };
    const body = JSON.stringify(message);
    const bytes = Buffer.byteLength(body, "utf8");
    if (bytes > 240_000) throw new Error(`SQS snapshot exceeds the 240KB application limit (${bytes} bytes)`);
    const response = await this.client.send(new SendMessageCommand({
      QueueUrl: this.queueUrl,
      MessageBody: body,
      MessageAttributes: {
        schemaVersion: { DataType: "Number", StringValue: "1" },
        snapshotDate: { DataType: "String", StringValue: snapshot.date },
        snapshotMode: { DataType: "String", StringValue: snapshot.mode },
      },
    }));
    await this.state.putJson(stateKey, {
      fingerprint,
      idempotencyKey,
      messageId: response.MessageId ?? null,
      publishedAt: new Date(this.now()).toISOString(),
    }, Math.floor(this.now() / 1000) + 45 * 86_400);
    return { changed: true, fingerprint, idempotencyKey, messageId: response.MessageId ?? null };
  }
}

export class MemoryPublisher {
  constructor() {
    this.messages = [];
    this.fingerprints = new Map();
  }

  async publishIfChanged(snapshot) {
    const fingerprint = snapshotFingerprint(snapshot);
    const key = `${snapshot.date}#${snapshot.mode}`;
    if (this.fingerprints.get(key) === fingerprint) return { changed: false, fingerprint };
    this.fingerprints.set(key, fingerprint);
    this.messages.push(structuredClone(snapshot));
    return { changed: true, fingerprint };
  }
}

function kstLocalTimestamp(value) {
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) throw new Error("Invalid one-time schedule timestamp");
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const get = (type) => parts.find((entry) => entry.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")}T${get("hour")}:${get("minute")}:${get("second")}`;
}

function comparableSchedule(value) {
  const target = value.Target ?? {};
  const ecs = target.EcsParameters ?? {};
  const network = ecs.NetworkConfiguration?.awsvpcConfiguration ?? {};
  return stableValue({
    ScheduleExpression: value.ScheduleExpression,
    ScheduleExpressionTimezone: value.ScheduleExpressionTimezone,
    State: value.State,
    ActionAfterCompletion: value.ActionAfterCompletion,
    FlexibleTimeWindow: value.FlexibleTimeWindow,
    Target: {
      Arn: target.Arn,
      RoleArn: target.RoleArn,
      DeadLetterConfig: { Arn: target.DeadLetterConfig?.Arn },
      RetryPolicy: {
        MaximumEventAgeInSeconds: target.RetryPolicy?.MaximumEventAgeInSeconds,
        MaximumRetryAttempts: target.RetryPolicy?.MaximumRetryAttempts,
      },
      EcsParameters: {
        EnableExecuteCommand: ecs.EnableExecuteCommand,
        LaunchType: ecs.LaunchType,
        NetworkConfiguration: {
          awsvpcConfiguration: {
            AssignPublicIp: network.AssignPublicIp,
            SecurityGroups: network.SecurityGroups,
            Subnets: network.Subnets,
          },
        },
        PlatformVersion: ecs.PlatformVersion,
        TaskCount: ecs.TaskCount,
        TaskDefinitionArn: ecs.TaskDefinitionArn,
      },
      Input: target.Input,
    },
    Description: value.Description,
  });
}

export class EcsScheduleManager {
  constructor(config, profileName, dependencies = {}) {
    this.config = config;
    this.profileName = profileName;
    this.client = dependencies.schedulerClient ?? new SchedulerClient({ region: config.aws.region });
  }

  scheduleName(dateKey) {
    return `inning-log-crawler-run-${dateKey}`;
  }

  request(dateKey, runAt) {
    const { aws } = this.config;
    return {
      Name: this.scheduleName(dateKey),
      GroupName: aws.schedulerGroup,
      Description: `Inning Log KBO game window for ${dateKey}`,
      ScheduleExpression: `at(${kstLocalTimestamp(runAt)})`,
      ScheduleExpressionTimezone: "Asia/Seoul",
      FlexibleTimeWindow: { Mode: "OFF" },
      State: "ENABLED",
      ActionAfterCompletion: "DELETE",
      Target: {
        Arn: aws.ecs.clusterArn,
        RoleArn: aws.schedulerRoleArn,
        DeadLetterConfig: { Arn: aws.schedulerDlqArn },
        RetryPolicy: {
          MaximumEventAgeInSeconds: 1_800,
          MaximumRetryAttempts: 2,
        },
        EcsParameters: {
          EnableExecuteCommand: false,
          LaunchType: "FARGATE",
          NetworkConfiguration: {
            awsvpcConfiguration: {
              AssignPublicIp: aws.ecs.assignPublicIp ? "ENABLED" : "DISABLED",
              SecurityGroups: aws.ecs.securityGroupIds,
              Subnets: aws.ecs.subnetIds,
            },
          },
          PlatformVersion: "LATEST",
          TaskCount: 1,
          TaskDefinitionArn: aws.ecs.taskDefinitionArn,
        },
        Input: JSON.stringify({
          containerOverrides: [{
            name: aws.ecs.containerName,
            command: [
              "--run-game-window",
              "--profile",
              this.profileName,
              "--date",
              dateKey,
            ],
          }],
        }),
      },
    };
  }

  async upsert(dateKey, runAt) {
    const desired = this.request(dateKey, runAt);
    let existing = null;
    try {
      existing = await this.client.send(new GetScheduleCommand({
        Name: desired.Name,
        GroupName: desired.GroupName,
      }));
    } catch (error) {
      if (!isNotFound(error)) throw error;
    }
    if (existing) {
      if (JSON.stringify(comparableSchedule(existing)) === JSON.stringify(comparableSchedule(desired))) {
        return { changed: false, action: "unchanged", name: desired.Name };
      }
      await this.client.send(new UpdateScheduleCommand(desired));
      return { changed: true, action: "updated", name: desired.Name };
    }
    try {
      await this.client.send(new CreateScheduleCommand(desired));
      return { changed: true, action: "created", name: desired.Name };
    } catch (error) {
      if (!(error instanceof ConflictException) && error?.name !== "ConflictException") throw error;
      await this.client.send(new UpdateScheduleCommand(desired));
      return { changed: true, action: "updated-after-race", name: desired.Name };
    }
  }

  async remove(dateKey) {
    try {
      await this.client.send(new DeleteScheduleCommand({
        Name: this.scheduleName(dateKey),
        GroupName: this.config.aws.schedulerGroup,
      }));
      return { changed: true, action: "deleted", name: this.scheduleName(dateKey) };
    } catch (error) {
      if (isNotFound(error)) return { changed: false, action: "absent", name: this.scheduleName(dateKey) };
      throw error;
    }
  }
}

export class MemoryScheduleManager {
  constructor() {
    this.schedules = new Map();
  }

  async upsert(dateKey, runAt) {
    const value = new Date(runAt).toISOString();
    const changed = this.schedules.get(dateKey) !== value;
    this.schedules.set(dateKey, value);
    return { changed, action: changed ? "upserted" : "unchanged", name: `memory-${dateKey}` };
  }

  async remove(dateKey) {
    const changed = this.schedules.delete(dateKey);
    return { changed, action: changed ? "deleted" : "absent", name: `memory-${dateKey}` };
  }
}

export { kstLocalTimestamp, snapshotFingerprint };
