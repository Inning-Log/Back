import { randomUUID } from "node:crypto";
import {
  allGamesTerminal,
  earliestScheduledAt,
  mergeKboPages,
} from "./kbo-pages.js";

const DAY_SECONDS = 86_400;

function iso(nowMs) {
  return new Date(nowMs).toISOString();
}

function expiry(nowMs, days = 45) {
  return Math.floor(nowMs / 1000) + days * DAY_SECONDS;
}

function abortableSleep(ms, signal) {
  if (signal?.aborted) return Promise.reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
  return new Promise((resolve, reject) => {
    const timer = setTimeout(resolve, ms);
    timer.unref?.();
    signal?.addEventListener("abort", () => {
      clearTimeout(timer);
      reject(signal.reason ?? new DOMException("Aborted", "AbortError"));
    }, { once: true });
  });
}

function snapshot({ config, source, dateKey, mode, observedAt, games, anomalies }) {
  return {
    schemaVersion: 1,
    source: source.kind === "fixture" ? "fixture-kbo-pages" : "kbo-pages",
    mode,
    date: dateKey,
    observedAt: iso(observedAt),
    games,
    anomalies,
    requestMetrics: source.getMetrics(),
  };
}

function monthlyScheduleSnapshot({ source, monthKey, observedAt, games, anomalies }) {
  return {
    schemaVersion: 1,
    source: source.kind === "fixture" ? "fixture-kbo-pages" : "kbo-pages",
    mode: "schedule-month",
    date: `${monthKey}-01`,
    month: monthKey,
    observedAt: iso(observedAt),
    games,
    anomalies,
    requestMetrics: source.getMetrics(),
  };
}

async function saveAndPublish({ state, publisher, value, nowMs }) {
  await state.putJson(`LATEST#${value.date}`, value, expiry(nowMs));
  return publisher.publishIfChanged(value);
}

async function saveMonthSchedule({ state, publisher, value, nowMs }) {
  await state.putJson(`SCHEDULE#MONTH#${value.month}`, value, expiry(nowMs, 400));
  return publisher.publishIfChanged(value);
}

function activeGames(games) {
  return games.filter((game) => !["FINISHED", "CANCELLED", "POSTPONED"].includes(game.status));
}

function windowStart(earliestAt, config) {
  return earliestAt == null ? null : earliestAt - config.polling.scoreboardLeadMs;
}

export async function runPlanDay(context) {
  const {
    config,
    source,
    state,
    publisher,
    scheduler,
    dateKey,
    dryRun = false,
  } = context;
  const now = context.now ?? (() => Date.now());
  const owner = context.owner ?? randomUUID();
  const leaseKey = `LEASE#PLAN#${dateKey}`;
  const start = now();
  const acquired = await state.acquireLease(
    leaseKey,
    owner,
    Math.floor(start / 1000) + 10 * 60,
    Math.floor(start / 1000)
  );
  if (!acquired) return { skipped: true, reason: "lease-held", date: dateKey };

  try {
    const monthlySchedule = typeof source.fetchScheduleMonth === "function"
      ? await source.fetchScheduleMonth(dateKey, { signal: context.signal })
      : null;
    const schedule = monthlySchedule == null
      ? await source.fetchSchedule(dateKey, { signal: context.signal })
      : {
        games: monthlySchedule.games.filter((game) => game.date === dateKey),
        anomalies: monthlySchedule.anomalies.filter((entry) => entry.date === dateKey),
      };
    const monthKey = dateKey.slice(0, 7);
    const monthValue = monthlySchedule == null
      ? null
      : monthlyScheduleSnapshot({
        source,
        monthKey,
        observedAt: now(),
        games: monthlySchedule.games,
        anomalies: monthlySchedule.anomalies,
      });
    const monthPublish = monthValue == null
      ? null
      : (dryRun
        ? { changed: false, dryRun: true }
        : await saveMonthSchedule({ state, publisher, value: monthValue, nowMs: now() }));
    const value = snapshot({
      config,
      source,
      dateKey,
      mode: "plan-day",
      observedAt: now(),
      games: schedule.games,
      anomalies: schedule.anomalies,
    });
    const publish = dryRun
      ? { changed: false, dryRun: true }
      : await saveAndPublish({ state, publisher, value, nowMs: now() });
    const earliest = earliestScheduledAt(activeGames(schedule.games));
    const runAt = earliest == null
      ? null
      : Math.max(now() + 2 * 60_000, earliest - config.polling.planLeadMs);
    let scheduleAction;
    if (dryRun) {
      scheduleAction = { changed: false, action: "dry-run" };
    } else if (earliest == null) {
      scheduleAction = await scheduler.remove(dateKey);
    } else {
      scheduleAction = await scheduler.upsert(dateKey, runAt);
    }
    if (!dryRun) {
      await state.putJson(`DAY#${dateKey}`, {
        date: dateKey,
        games: schedule.games,
        plannedAt: iso(now()),
        runAt: runAt == null ? null : iso(runAt),
      }, expiry(now()));
    }
    return {
      skipped: false,
      date: dateKey,
      gameCount: schedule.games.length,
      activeGameCount: activeGames(schedule.games).length,
      month: monthValue == null ? null : monthKey,
      monthGameCount: monthValue?.games.length ?? null,
      monthPublish,
      anomalies: schedule.anomalies,
      publish,
      schedule: scheduleAction,
      requestMetrics: source.getMetrics(),
    };
  } finally {
    await state.releaseLease(leaseKey, owner);
  }
}

export async function runGameWindow(context) {
  const {
    config,
    source,
    state,
    publisher,
    dateKey,
  } = context;
  const now = context.now ?? (() => Date.now());
  const sleep = context.sleep ?? abortableSleep;
  const signal = context.signal;
  const owner = context.owner ?? randomUUID();
  const leaseKey = `LEASE#WINDOW#${dateKey}`;
  const startedAt = now();
  const deadline = startedAt + config.polling.hardTimeoutMs;
  const leaseTtlMs = Math.max(10 * 60_000, config.polling.leaseRenewMs * 2);
  const acquired = await state.acquireLease(
    leaseKey,
    owner,
    Math.floor((startedAt + leaseTtlMs) / 1000),
    Math.floor(startedAt / 1000)
  );
  if (!acquired) return { skipped: true, reason: "lease-held", date: dateKey };

  let nextLeaseRenewAt = startedAt + config.polling.leaseRenewMs;
  try {
    let scheduleResult = await source.fetchSchedule(dateKey, { signal });
    let scoreboardResult = { games: [], anomalies: [] };
    let combined = mergeKboPages(scheduleResult.games, scoreboardResult.games);
    let value = snapshot({
      config,
      source,
      dateKey,
      mode: "game-window",
      observedAt: now(),
      games: combined.games,
      anomalies: [...scheduleResult.anomalies, ...combined.anomalies],
    });
    await saveAndPublish({ state, publisher, value, nowMs: now() });

    const earliest = earliestScheduledAt(activeGames(scheduleResult.games));
    if (earliest == null) {
      return {
        skipped: false,
        reason: "no-active-games",
        date: dateKey,
        gameCount: combined.games.length,
        requestMetrics: source.getMetrics(),
      };
    }

    let nextScheduleAt = now() + config.polling.scheduleRefreshMs;
    let nextScoreboardAt = Math.max(now(), windowStart(earliest, config));
    let terminalDetectedAt = null;
    let completedFinalChecks = 0;
    let scoreboardPolled = false;

    while (now() < deadline) {
      if (signal?.aborted) throw signal.reason ?? new DOMException("Aborted", "AbortError");
      const current = now();
      let changed = false;
      let cancelledBeforeScoreboard = false;

      if (current >= nextLeaseRenewAt) {
        const renewed = await state.renewLease(
          leaseKey,
          owner,
          Math.floor((current + leaseTtlMs) / 1000),
          Math.floor(current / 1000)
        );
        if (!renewed) {
          const error = new Error("Game-window lease was lost; stopping to prevent duplicate requests");
          error.code = "LEASE_LOST";
          throw error;
        }
        nextLeaseRenewAt = current + config.polling.leaseRenewMs;
      }

      if (current >= nextScheduleAt) {
        scheduleResult = await source.fetchSchedule(dateKey, { signal });
        nextScheduleAt = now() + config.polling.scheduleRefreshMs;
        if (!scoreboardPolled) {
          const revisedEarliest = earliestScheduledAt(activeGames(scheduleResult.games));
          if (revisedEarliest != null) {
            nextScoreboardAt = Math.max(now(), windowStart(revisedEarliest, config));
          } else if (
            scheduleResult.games.length > 0
            && scheduleResult.games.every((game) => ["CANCELLED", "POSTPONED"].includes(game.status))
          ) {
            cancelledBeforeScoreboard = true;
          }
        }
        changed = true;
      }

      if (!cancelledBeforeScoreboard && current >= nextScoreboardAt) {
        scoreboardResult = await source.fetchScoreboard(dateKey, { signal });
        scoreboardPolled = true;
        changed = true;
        const preview = mergeKboPages(scheduleResult.games, scoreboardResult.games);
        if (allGamesTerminal(preview.games)) {
          if (terminalDetectedAt == null) {
            terminalDetectedAt = now();
            completedFinalChecks = 0;
          } else {
            completedFinalChecks += 1;
          }
          if (completedFinalChecks >= config.polling.finalCheckOffsetsMs.length) {
            combined = preview;
            value = snapshot({
              config,
              source,
              dateKey,
              mode: "game-window",
              observedAt: now(),
              games: combined.games,
              anomalies: [
                ...scheduleResult.anomalies,
                ...scoreboardResult.anomalies,
                ...combined.anomalies,
              ],
            });
            await saveAndPublish({ state, publisher, value, nowMs: now() });
            return {
              skipped: false,
              reason: "final-checks-complete",
              date: dateKey,
              gameCount: combined.games.length,
              finalChecks: completedFinalChecks,
              requestMetrics: source.getMetrics(),
            };
          }
          nextScoreboardAt = terminalDetectedAt + config.polling.finalCheckOffsetsMs[completedFinalChecks];
        } else {
          terminalDetectedAt = null;
          completedFinalChecks = 0;
          const delayed = preview.games.some((game) => ["DELAYED", "SUSPENDED"].includes(game.status));
          nextScoreboardAt = now() + (delayed
            ? config.polling.delayedRefreshMs
            : config.polling.scoreboardRefreshMs);
        }
      }

      if (changed) {
        combined = mergeKboPages(scheduleResult.games, scoreboardResult.games);
        value = snapshot({
          config,
          source,
          dateKey,
          mode: "game-window",
          observedAt: now(),
          games: combined.games,
          anomalies: [
            ...scheduleResult.anomalies,
            ...scoreboardResult.anomalies,
            ...combined.anomalies,
          ],
        });
        await saveAndPublish({ state, publisher, value, nowMs: now() });
        const scheduleCancelled = scheduleResult.games.length > 0
          && scheduleResult.games.every((game) => ["CANCELLED", "POSTPONED"].includes(game.status));
        const liveObserved = scoreboardResult.games.some((game) => game.status === "LIVE");
        if (scheduleCancelled && !liveObserved) {
          return {
            skipped: false,
            reason: "all-games-cancelled-or-postponed",
            date: dateKey,
            gameCount: combined.games.length,
            finalChecks: completedFinalChecks,
            requestMetrics: source.getMetrics(),
          };
        }
      }

      const wakeAt = Math.min(nextScheduleAt, nextScoreboardAt, nextLeaseRenewAt, deadline);
      await sleep(Math.max(1, wakeAt - now()), signal);
    }
    return {
      skipped: false,
      reason: "hard-timeout",
      date: dateKey,
      gameCount: combined.games.length,
      finalChecks: completedFinalChecks,
      requestMetrics: source.getMetrics(),
    };
  } finally {
    await state.releaseLease(leaseKey, owner);
  }
}

export function todayInSeoul(now = new Date()) {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(now);
  const get = (type) => parts.find((entry) => entry.type === type)?.value;
  return `${get("year")}-${get("month")}-${get("day")}`;
}

export function isStrictDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value ?? ""))) return false;
  const parsed = new Date(`${value}T00:00:00Z`);
  return Number.isFinite(parsed.getTime()) && parsed.toISOString().slice(0, 10) === value;
}
