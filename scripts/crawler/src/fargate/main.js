import path from "node:path";
import process from "node:process";
import { randomUUID } from "node:crypto";
import { fileURLToPath } from "node:url";
import {
  FargateConfigError,
  assertAwsRuntimeConfig,
  assertLivePolicy,
  evaluateLivePolicy,
  loadFargateConfig,
  redactFargateConfig,
} from "./config.js";
import {
  DynamoStateStore,
  EcsScheduleManager,
  MemoryPublisher,
  MemoryScheduleManager,
  MemoryStateStore,
  SnapshotPublisher,
} from "./aws.js";
import { createKboPageSource } from "./source.js";
import { safeError } from "./diagnostics.js";
export { safeError } from "./diagnostics.js";
import { isStrictDate, runCollectOnce, runGameWindow, runPlanDay, todayInSeoul } from "./workflow.js";

export function parseFargateArgs(argv) {
  const output = {
    action: null,
    profile: null,
    configPath: null,
    date: null,
    dryRun: false,
    printConfig: false,
  };
  const actionFlags = new Map([
    ["--plan-day", "plan-day"],
    ["--collect-once", "collect-once"],
    ["--run-game-window", "run-game-window"],
    ["--check-config", "check-config"],
    ["--check-policy", "check-policy"],
    ["--help", "help"],
  ]);
  const seen = new Set();
  for (let index = 0; index < argv.length; index += 1) {
    const raw = argv[index];
    const [flag, inline] = raw.split("=", 2);
    if (actionFlags.has(flag)) {
      if (inline != null) throw new FargateConfigError(`${flag} does not accept a value`);
      if (output.action) throw new FargateConfigError(`Conflicting actions: ${output.action} and ${flag}`);
      output.action = actionFlags.get(flag);
      continue;
    }
    if (flag === "--dry-run" || flag === "--print-config") {
      if (inline != null || seen.has(flag)) throw new FargateConfigError(`Invalid or duplicate flag: ${raw}`);
      seen.add(flag);
      if (flag === "--dry-run") output.dryRun = true;
      else output.printConfig = true;
      continue;
    }
    const targets = {
      "--profile": "profile",
      "--config": "configPath",
      "--date": "date",
    };
    if (targets[flag]) {
      if (seen.has(flag)) throw new FargateConfigError(`Duplicate flag: ${flag}`);
      seen.add(flag);
      const value = inline ?? argv[++index];
      if (!value || value.startsWith("--")) throw new FargateConfigError(`${flag} requires a value`);
      output[targets[flag]] = value;
      continue;
    }
    throw new FargateConfigError(`Unknown argument: ${raw}`);
  }
  if (!output.action) throw new FargateConfigError("One action is required; use --help");
  if (output.date && !isStrictDate(output.date)) throw new FargateConfigError("--date must be YYYY-MM-DD");
  if (["run-game-window", "collect-once"].includes(output.action) && output.dryRun) {
    throw new FargateConfigError("--dry-run cannot start a game-window loop; use --plan-day --dry-run");
  }
  return output;
}

export function formatFargateHelp() {
  return `Inning Log KBO page-only Fargate crawler

Actions (choose one):
  --collect-once         Fetch month + scoreboard once, publish, and verify state read-back
  --plan-day             Read today's schedule and upsert one game-window task
  --run-game-window      Poll schedule/scoreboard only during the bounded game window
  --check-config         Validate configuration without AWS or KBO access
  --check-policy         Evaluate the live-access gate without AWS or KBO access
  --help                 Show this help

Options:
  --profile NAME         fixture (default) | fixture-aws | kbo-locked | kbo-live
  --config PATH          Configuration file (default: config/fargate.yml)
  --date YYYY-MM-DD      Defaults to today's date in Asia/Seoul
  --dry-run              Zero-network plan inspection; valid with --plan-day only
  --print-config         Print redacted effective configuration

The live source uses Schedule.aspx's GetScheduleList request and ScoreBoard.aspx. The safe image
default is: --plan-day --profile fixture --dry-run.`;
}

function logJson(value) {
  console.info(JSON.stringify(value));
}

function runtimeDependencies(config, profile, dryRun, dependencies) {
  if (config.runtime.persistence === "memory" || dryRun) {
    const state = dependencies.state ?? new MemoryStateStore();
    return {
      state,
      publisher: dependencies.publisher ?? new MemoryPublisher(),
      scheduler: dependencies.scheduler ?? new MemoryScheduleManager(),
    };
  }
  assertAwsRuntimeConfig(config);
  const state = dependencies.state ?? new DynamoStateStore(config, dependencies);
  return {
    state,
    publisher: dependencies.publisher ?? new SnapshotPublisher(config, state, dependencies),
    scheduler: dependencies.scheduler ?? new EcsScheduleManager(config, profile, dependencies),
  };
}

export async function main(argv = process.argv.slice(2), environment = process.env, dependencies = {}) {
  const args = parseFargateArgs(argv);
  if (args.action === "help") {
    console.info(formatFargateHelp());
    return { action: "help" };
  }
  const loaded = await loadFargateConfig({
    env: environment,
    profile: args.profile,
    configPath: args.configPath,
    root: dependencies.root,
  });
  const { config, profile } = loaded;
  const dateKey = args.date ?? todayInSeoul(new Date(dependencies.now?.() ?? Date.now()));
  if (config.provider === "kbo" && dateKey !== todayInSeoul(new Date(dependencies.now?.() ?? Date.now()))) {
    throw new FargateConfigError("The two reviewed KBO pages support only today's live workflow; --date must be today in Asia/Seoul");
  }
  if (args.printConfig) logJson({ profile, config: redactFargateConfig(config) });
  if (args.action === "check-config") {
    const result = { action: "check-config", ok: true, profile, configPath: loaded.configPath };
    logJson(result);
    return result;
  }
  const policy = evaluateLivePolicy(config, new Date(dependencies.now?.() ?? Date.now()));
  if (args.action === "check-policy") {
    const result = { action: "check-policy", profile, ...policy };
    logJson(result);
    return result;
  }

  if (args.dryRun) {
    const result = {
      action: "plan-day",
      profile,
      date: dateKey,
      dryRun: true,
      externalRequests: 0,
      policy,
      endpoints: config.provider === "kbo" ? Object.values(config.endpoints) : [],
    };
    logJson(result);
    return result;
  }

  assertLivePolicy(config, new Date(dependencies.now?.() ?? Date.now()));
  const runId = dependencies.owner ?? randomUUID();
  const log = (event) => (dependencies.log ?? logJson)({
    ...event, runId, action: args.action, profile, date: dateKey,
    timestamp: new Date(dependencies.now?.() ?? Date.now()).toISOString(),
  });
  const runtime = runtimeDependencies(config, profile, false, dependencies);
  const source = dependencies.source ?? createKboPageSource(config, {
    ...dependencies,
    state: runtime.state,
    log,
  });
  const controller = dependencies.controller ?? new AbortController();
  const onSignal = (name) => controller.abort(new DOMException(`Received ${name}`, "AbortError"));
  const sigterm = () => onSignal("SIGTERM");
  const sigint = () => onSignal("SIGINT");
  if (!dependencies.controller) {
    process.once("SIGTERM", sigterm);
    process.once("SIGINT", sigint);
  }
  try {
    log({ level: "info", event: "run_started" });
    const context = {
      config,
      profile,
      source,
      ...runtime,
      dateKey,
      signal: controller.signal,
      now: dependencies.now,
      sleep: dependencies.sleep,
      owner: runId,
      log,
    };
    const result = args.action === "collect-once"
      ? await runCollectOnce(context)
      : args.action === "plan-day"
      ? await runPlanDay(context)
      : await runGameWindow(context);
    log({ level: result.reason === "hard-timeout" ? "error" : "info", event: "run_completed", ...result });
    return result;
  } catch (error) {
    log({ ...safeError(error), event: "run_failed", requestMetrics: source.getMetrics() });
    throw error;
  } finally {
    if (!dependencies.controller) {
      process.off("SIGTERM", sigterm);
      process.off("SIGINT", sigint);
    }
  }
}

const currentFile = path.resolve(fileURLToPath(import.meta.url));
const invokedFile = process.argv[1] ? path.resolve(process.argv[1]) : null;
export function resultExitCode(result) {
  if (result?.action === "check-policy" && result.ok === false) return 2;
  return result?.reason === "hard-timeout" ? 1 : 0;
}
if (invokedFile === currentFile) {
  main()
    .then((result) => {
      process.exitCode = resultExitCode(result);
    })
    .catch((error) => {
      console.error(JSON.stringify(safeError(error)));
      process.exitCode = error?.name === "AbortError" ? 0 : 1;
    });
}
