import assert from "node:assert/strict";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import { loadCrawlerConfig } from "../src/config.js";
import { runOnce } from "../src/crawler.js";

async function fixtureConfig() {
  const loaded = await loadCrawlerConfig({
    argv: [],
    env: {},
    warn: () => {},
  });
  return structuredClone(loaded.config);
}

function approveExternal(config, providerName, scopes) {
  config.identity.contact = "mailto:operator@example.com";
  config.policy.allowUndocumentedApi = true;
  config.providers[providerName].enabled = true;
  config.providers[providerName].authorization = {
    status: "approved",
    evidence: `written-${providerName}-permission`,
    reviewedAt: "2026-08-25T09:00:00+09:00",
    expiresAt: "2026-09-25T09:00:00+09:00",
    scopes,
    robotsExceptionGranted: true,
    undocumentedApiApproved: true,
  };
}

test("fixture orchestration normalizes games with zero external requests", async () => {
  const config = await fixtureConfig();
  config.output.enabled = false;
  config.run.maxGamesPerRun = 1;

  const result = await runOnce(config, {
    now: new Date("2026-08-26T12:00:00+09:00"),
  });

  assert.equal(result.snapshot.total, 1);
  assert.equal(result.snapshot.date, "2026-08-26");
  assert.equal(result.snapshot.requestMetrics.logicalRequests, 0);
  assert.equal(result.snapshot.requestMetrics.requestAttempts, 0);
  assert.equal(result.saved, false);
});

test("blocked external source cannot reach an injected HTTP collector", async () => {
  const config = await fixtureConfig();
  config.source = "kbo";
  config.output.enabled = false;
  let called = false;
  const http = {
    requestText: async () => {
      called = true;
      throw new Error("must not execute");
    },
    getMetrics: () => ({ logicalRequests: 0, requestAttempts: 0 }),
  };

  await assert.rejects(
    runOnce(config, {
      http,
      now: new Date("2026-08-26T12:00:00+09:00"),
    }),
    (error) => error?.code === "POLICY_CHECK_FAILED"
  );
  assert.equal(called, false);
});

test("dry-run returns a zero-network plan before reading fixture or writing files", async () => {
  const config = await fixtureConfig();
  config.output.dryRun = true;
  config.output.enabled = false;
  config.providers.fixture.file = "Z:\\definitely-missing\\fixture.json";

  const result = await runOnce(config, {
    now: new Date("2026-08-26T12:00:00+09:00"),
  });
  assert.equal(result.dryRun, true);
  assert.equal(result.plan.externalNetwork, false);
  assert.equal(result.plan.logicalRequestsAtMost, 0);
  assert.equal(result.plan.outputEnabled, false);
});

test("authorized Naver orchestration joins schedule and nested live detail without real network", async () => {
  const config = await fixtureConfig();
  config.source = "naver";
  config.output.enabled = false;
  config.run.maxDetailGamesPerRun = 1;
  approveExternal(config, "naver", [
    "schedule",
    "relay",
    "record",
    "robots-disallow-override",
  ]);

  const calls = [];
  const http = {
    resetRunBudget: () => {},
    requestJson: async (_provider, capability) => {
      calls.push(capability);
      if (capability === "schedule") {
        return {
          data: {
            games: [
              {
                gameId: "NAVER-INTEGRATION-1",
                gameDate: "20260826",
                startTime: "18:30",
                homeTeam: { name: "LG 트윈스", score: 1 },
                awayTeam: { name: "두산 베어스", score: 0 },
                stadiumName: "잠실",
                gameState: "경기중",
                currentInning: 3,
                isTop: true,
              },
            ],
          },
        };
      }
      return {
        result: {
          status: "경기중",
          startTime: "18:32",
          homeScore: 2,
          awayScore: 0,
          currentInning: 3,
          isTop: false,
          plays: [
            {
              id: "play-integration-1",
              inning: "3회말",
              batterName: "샘플 타자",
              description: "좌전 안타",
            },
          ],
        },
      };
    },
    getMetrics: () => ({ logicalRequests: 2, requestAttempts: 0, circuits: {} }),
    tripCircuit: () => {},
  };

  const result = await runOnce(config, {
    http,
    now: new Date("2026-08-26T19:00:00+09:00"),
  });
  assert.deepEqual(calls, ["schedule", "relay"]);
  assert.equal(result.snapshot.partial, false);
  assert.equal(result.snapshot.games[0].score.home, 2);
  assert.equal(result.snapshot.games[0].half, "BOTTOM");
  assert.equal(result.snapshot.games[0].events[0].eventType, "SINGLE");
  assert.equal(result.snapshot.games[0].events[0].description, null);
});

test("orchestration writes atomically and skips unchanged fixture content", async () => {
  const temporaryRoot = await mkdtemp(
    path.join(os.tmpdir(), "inning-log-crawler-run-")
  );
  try {
    const config = await fixtureConfig();
    config.output.directory = path.join(temporaryRoot, "out");
    config.output.enabled = true;
    config.data.maxEventsPerGame = 0;

    const first = await runOnce(config, {
      now: new Date("2026-08-26T12:00:00+09:00"),
      crawlerRoot: temporaryRoot,
    });
    const second = await runOnce(config, {
      now: new Date("2026-08-26T12:05:00+09:00"),
      crawlerRoot: temporaryRoot,
    });

    assert.equal(first.saved.changed, true);
    assert.equal(second.saved.changed, false);
    const snapshot = JSON.parse(
      await readFile(first.saved.snapshotPath, "utf8")
    );
    assert.equal(snapshot.total, 2);
    assert.ok(snapshot.games.every((entry) => entry.events.length === 0));
    assert.match(snapshot.contentFingerprint, /^[a-f0-9]{64}$/);
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});
