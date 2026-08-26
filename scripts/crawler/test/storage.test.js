import test from "node:test";
import assert from "node:assert/strict";
import { promises as fs } from "node:fs";
import os from "node:os";
import path from "node:path";
import {
  contentFingerprint,
  legacySnapshotContentFingerprint,
  loadCrawlerState,
  saveSnapshot,
  snapshotContentFingerprint,
  withRunLock,
} from "../src/storage.js";

test("수집 시각만 달라지면 content fingerprint는 같아야 한다", () => {
  const first = contentFingerprint({ crawledAt: "2026-01-01T00:00:00Z", games: [{ score: 1 }] });
  const second = contentFingerprint({ crawledAt: "2026-01-01T00:01:00Z", games: [{ score: 1 }] });
  assert.equal(first, second);
});

test("snapshot fingerprint는 오류 문구 변동은 무시하고 오류 코드 변경은 감지한다", () => {
  const base = {
    source: "hybrid",
    date: "2026-08-26",
    partial: true,
    games: [],
    providerErrors: [{ provider: "kbo", code: "REQUEST_FAILED", message: "a" }],
  };
  assert.equal(
    snapshotContentFingerprint(base),
    snapshotContentFingerprint({
      ...base,
      providerErrors: [{ provider: "kbo", code: "REQUEST_FAILED", message: "b" }],
    })
  );
  assert.notEqual(
    snapshotContentFingerprint(base),
    snapshotContentFingerprint({
      ...base,
      providerErrors: [
        { provider: "kbo", code: "PROVIDER_SCHEMA_MISMATCH", message: "a" },
      ],
    })
  );
  assert.notEqual(
    legacySnapshotContentFingerprint(base),
    snapshotContentFingerprint(base),
    "legacy fingerprints are intentionally migrated on the next save"
  );
});

test("동일 경기 내용은 snapshot 재기록을 건너뛴다", async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "inning-log-storage-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  const config = {
    output: { directory, filePrefix: "games" },
  };
  const loaded = await loadCrawlerState(directory);
  const base = {
    source: "hybrid",
    date: "2026-08-25",
    crawledAt: "2026-08-25T00:00:00Z",
    partial: true,
    providerErrors: [
      { provider: "kbo", code: "REQUEST_FAILED", message: "first message" },
    ],
    games: [{ id: "g1", score: { home: 1, away: 0 } }],
  };
  const first = await saveSnapshot({
    snapshot: base,
    dateKey: base.date,
    config,
    crawlerRoot: directory,
    ...loaded,
  });
  const second = await saveSnapshot({
    snapshot: {
      ...base,
      crawledAt: "2026-08-25T00:05:00Z",
      providerErrors: [
        { provider: "kbo", code: "REQUEST_FAILED", message: "changed wording" },
      ],
    },
    dateKey: base.date,
    config,
    crawlerRoot: directory,
    ...loaded,
  });
  assert.equal(first.changed, true);
  assert.equal(second.changed, false);

  const third = await saveSnapshot({
    snapshot: {
      ...base,
      crawledAt: "2026-08-25T00:10:00Z",
      providerErrors: [
        { provider: "kbo", code: "PROVIDER_SCHEMA_MISMATCH", message: "schema" },
      ],
    },
    dateKey: base.date,
    config,
    crawlerRoot: directory,
    ...loaded,
  });
  assert.equal(third.changed, true);
});

test("손상된 state collection shape는 빈 상태로 추정하지 않고 차단한다", async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "inning-log-state-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  await fs.writeFile(
    path.join(directory, ".crawler-state.json"),
    JSON.stringify({ version: 1, snapshots: [], polling: {}, providers: {} })
  );
  await assert.rejects(
    loadCrawlerState(directory),
    (error) => error?.code === "STATE_SHAPE_INVALID"
  );
});

test("run lock rejects overlapping executions before their operations overlap", async (t) => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), "inning-log-lock-"));
  t.after(() => fs.rm(directory, { recursive: true, force: true }));
  let enter;
  let release;
  const entered = new Promise((resolve) => {
    enter = resolve;
  });
  const gate = new Promise((resolve) => {
    release = resolve;
  });

  const first = withRunLock(directory, {}, async () => {
    enter();
    await gate;
    return "done";
  });
  await entered;
  await assert.rejects(
    withRunLock(directory, {}, async () => "must-not-run"),
    (error) => error?.code === "RUN_ALREADY_ACTIVE"
  );
  release();
  assert.equal(await first, "done");
});
