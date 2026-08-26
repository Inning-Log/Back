import { createHash } from "node:crypto";
import { promises as fs } from "node:fs";
import path from "node:path";

export class StorageError extends Error {
  constructor(message, code = "STORAGE_ERROR", cause = null) {
    super(message, cause ? { cause } : undefined);
    this.name = "StorageError";
    this.code = code;
  }
}

const VOLATILE_KEYS = new Set([
  "crawledAt",
  "retrievedAt",
  "observedAt",
  "relayFetchedAt",
  "recordFetchedAt",
  "lastPolledAt",
]);

function isRecord(value) {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

export function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue);
  if (!value || typeof value !== "object") return value;
  return Object.fromEntries(
    Object.keys(value)
      .filter((key) => !VOLATILE_KEYS.has(key))
      .sort()
      .map((key) => [key, stableValue(value[key])])
  );
}

export function contentFingerprint(value) {
  return createHash("sha256")
    .update(JSON.stringify(stableValue(value)))
    .digest("hex");
}

export function snapshotContentFingerprint(snapshot) {
  return contentFingerprint({
    source: snapshot.source,
    date: snapshot.date,
    games: snapshot.games,
    anomalies: snapshot.anomalies ?? [],
    partial: snapshot.partial ?? false,
    providerErrors: (snapshot.providerErrors ?? []).map((entry) => ({
      provider: entry.provider,
      code: entry.code,
    })),
  });
}

export function legacySnapshotContentFingerprint(snapshot) {
  return contentFingerprint({
    date: snapshot.date,
    games: snapshot.games,
    anomalies: snapshot.anomalies ?? [],
    partial: snapshot.partial ?? false,
  });
}

export async function readJsonStrict(filePath, options = {}) {
  try {
    const raw = await fs.readFile(filePath, "utf8");
    return JSON.parse(raw);
  } catch (error) {
    if (error?.code === "ENOENT" && Object.hasOwn(options, "fallback")) {
      return options.fallback;
    }
    if (error instanceof SyntaxError) {
      throw new StorageError(
        `JSON 파일이 손상되었습니다: ${filePath}`,
        "INVALID_JSON_FILE",
        error
      );
    }
    throw new StorageError(
      `JSON 파일을 읽지 못했습니다: ${filePath}`,
      "READ_FAILED",
      error
    );
  }
}

export async function writeJsonAtomic(filePath, value) {
  const directory = path.dirname(filePath);
  await fs.mkdir(directory, { recursive: true });
  const temporaryPath = path.join(
    directory,
    `.${path.basename(filePath)}.${process.pid}.${Date.now()}.tmp`
  );
  let handle;
  try {
    handle = await fs.open(temporaryPath, "wx", 0o600);
    await handle.writeFile(`${JSON.stringify(value, null, 2)}\n`, "utf8");
    await handle.sync();
    await handle.close();
    handle = null;
    await fs.rename(temporaryPath, filePath);
  } catch (error) {
    if (handle) await handle.close().catch(() => {});
    await fs.unlink(temporaryPath).catch(() => {});
    throw new StorageError(
      `JSON 파일을 원자적으로 저장하지 못했습니다: ${filePath}`,
      "ATOMIC_WRITE_FAILED",
      error
    );
  }
}

export function resolveOutputDirectory(config, crawlerRoot) {
  const configured = config.output?.directory ?? "./out";
  return path.isAbsolute(configured)
    ? configured
    : path.resolve(crawlerRoot, configured);
}

export async function loadCrawlerState(outputDirectory, stateFileName = ".crawler-state.json") {
  const statePath = path.join(outputDirectory, stateFileName);
  const state = await readJsonStrict(statePath, {
    fallback: {
      version: 1,
      snapshots: {},
      polling: {},
      providers: {},
    },
  });
  if (state?.version !== 1 || !isRecord(state)) {
    throw new StorageError(
      `지원하지 않는 crawler state 형식입니다: ${statePath}`,
      "STATE_VERSION_UNSUPPORTED"
    );
  }
  for (const field of ["snapshots", "polling", "providers"]) {
    if (state[field] === undefined || state[field] === null) state[field] = {};
    if (!isRecord(state[field])) {
      throw new StorageError(
        `crawler state의 ${field}가 객체가 아닙니다: ${statePath}`,
        "STATE_SHAPE_INVALID"
      );
    }
  }
  return { state, statePath };
}

export async function saveSnapshot({
  snapshot,
  dateKey,
  config,
  crawlerRoot,
  state,
  statePath,
  forceWrite = false,
}) {
  const outputDirectory = resolveOutputDirectory(config, crawlerRoot);
  const prefix = config.output?.filePrefix ?? "games";
  const snapshotPath = path.join(outputDirectory, `${prefix}-${dateKey}.json`);
  const fingerprint = snapshotContentFingerprint(snapshot);
  const previous = state.snapshots?.[dateKey];
  const changed = forceWrite || previous?.contentFingerprint !== fingerprint;

  const finalSnapshot = {
    ...snapshot,
    contentFingerprint: fingerprint,
  };
  if (changed) await writeJsonAtomic(snapshotPath, finalSnapshot);

  state.snapshots[dateKey] = {
    contentFingerprint: fingerprint,
    path: snapshotPath,
    lastPolledAt: snapshot.crawledAt,
    lastChangedAt: changed
      ? snapshot.crawledAt
      : previous?.lastChangedAt ?? snapshot.crawledAt,
  };
  await writeJsonAtomic(statePath, state);

  return { changed, snapshotPath, contentFingerprint: fingerprint };
}

function processAppearsAlive(pid) {
  if (!Number.isInteger(pid) || pid <= 0) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return error?.code === "EPERM";
  }
}

export async function withRunLock(outputDirectory, options, operation) {
  const lockPath = path.join(
    outputDirectory,
    options?.fileName ?? ".crawler.lock"
  );
  const staleAfterMs = options?.staleAfterMs ?? 30 * 60 * 1000;
  await fs.mkdir(outputDirectory, { recursive: true });

  let handle;
  try {
    handle = await fs.open(lockPath, "wx", 0o600);
  } catch (error) {
    if (error?.code !== "EEXIST") {
      throw new StorageError("실행 lock을 만들지 못했습니다.", "LOCK_CREATE_FAILED", error);
    }
    const existing = await readJsonStrict(lockPath, { fallback: null });
    const createdAt = Date.parse(existing?.createdAt ?? "");
    const stale =
      !Number.isFinite(createdAt) || Date.now() - createdAt > staleAfterMs;
    if (!stale || processAppearsAlive(Number(existing?.pid))) {
      throw new StorageError(
        `다른 crawler 실행이 lock을 보유 중입니다(pid=${existing?.pid ?? "unknown"}).`,
        "RUN_ALREADY_ACTIVE"
      );
    }

    const stalePath = `${lockPath}.stale-${Date.now()}`;
    await fs.rename(lockPath, stalePath);
    handle = await fs.open(lockPath, "wx", 0o600);
  }

  await handle.writeFile(
    `${JSON.stringify({ pid: process.pid, createdAt: new Date().toISOString() })}\n`,
    "utf8"
  );
  await handle.sync();

  try {
    return await operation();
  } finally {
    await handle.close().catch(() => {});
    await fs.unlink(lockPath).catch(() => {});
  }
}
