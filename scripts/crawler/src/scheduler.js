const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;

export const DEFAULT_POLLING = Object.freeze({
  farFutureMs: 6 * HOUR_MS,
  gameDayMs: 15 * MINUTE_MS,
  nearStartMs: 60_000,
  liveScheduleMs: 60_000,
  liveDetailMs: 2 * MINUTE_MS,
  delayedDetailMs: 5 * MINUTE_MS,
  finishedOffsetsMs: [5 * MINUTE_MS, 30 * MINUTE_MS, 90 * MINUTE_MS],
});

function millis(value, fallback) {
  return Number.isFinite(value) ? value : fallback;
}

function pollingConfig(config) {
  const source = config.strategy?.polling ?? {};
  return {
    farFutureMs: millis(source.farFutureMs, DEFAULT_POLLING.farFutureMs),
    gameDayMs: millis(source.gameDayMs, DEFAULT_POLLING.gameDayMs),
    nearStartMs: millis(source.nearStartMs, DEFAULT_POLLING.nearStartMs),
    liveScheduleMs: millis(source.liveScheduleMs, DEFAULT_POLLING.liveScheduleMs),
    liveDetailMs: millis(source.liveDetailMs, DEFAULT_POLLING.liveDetailMs),
    delayedDetailMs: millis(source.delayedDetailMs, DEFAULT_POLLING.delayedDetailMs),
    finishedOffsetsMs:
      source.finishedOffsetsMs ?? DEFAULT_POLLING.finishedOffsetsMs,
  };
}

function timeOf(value) {
  if (!value) return null;
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) ? timestamp : null;
}

export function pollingKey(game) {
  return (
    game.id ??
    game.gameId ??
    game.externalIds?.naver ??
    game.externalId?.naver ??
    game.externalIds?.kbo ??
    game.externalId?.kbo ??
    [
      game.date,
      game.homeTeam?.code ?? game.homeTeam?.name,
      game.awayTeam?.code ?? game.awayTeam?.name,
      game.scheduledAt,
      game.gameNumber ?? game.doubleheaderNumber,
    ]
      .filter((part) => part != null && part !== "")
      .join("|")
  );
}

export function recommendedScheduleInterval(games, targetDate, config, nowMs = Date.now()) {
  const polling = pollingConfig(config);
  const statuses = new Set(games.map((game) => game.status));
  if (statuses.has("LIVE")) {
    return polling.liveScheduleMs;
  }

  const starts = games
    .map((game) => timeOf(game.scheduledAt))
    .filter(Number.isFinite)
    .sort((a, b) => a - b);
  const nextStart = starts.find((start) => start >= nowMs);
  if (nextStart != null && nextStart - nowMs <= HOUR_MS) {
    return polling.nearStartMs;
  }

  const targetMs = timeOf(`${targetDate}T12:00:00+09:00`);
  if (targetMs != null && targetMs - nowMs > 24 * HOUR_MS) {
    return polling.farFutureMs;
  }
  if (targetMs != null && targetMs >= nowMs - 24 * HOUR_MS) {
    return polling.gameDayMs;
  }
  return polling.farFutureMs;
}

export function shouldPollSchedule({
  state,
  dateKey,
  previousGames = [],
  config,
  nowMs = Date.now(),
  force = false,
}) {
  if (force) return true;
  const lastPolledAt = timeOf(state.snapshots?.[dateKey]?.lastPolledAt);
  if (lastPolledAt == null) return true;
  const interval = recommendedScheduleInterval(
    previousGames,
    dateKey,
    config,
    nowMs
  );
  return nowMs - lastPolledAt >= interval;
}

function nextFinishedEligibility(game, pollState, config) {
  if (pollState?.recordComplete) return Number.POSITIVE_INFINITY;
  const polling = pollingConfig(config);
  const endedAt =
    timeOf(game.endedAt) ??
    (timeOf(game.scheduledAt) != null
      ? timeOf(game.scheduledAt) + 4 * HOUR_MS
      : null);
  if (endedAt == null) return Number.POSITIVE_INFINITY;
  const completedPolls = Math.max(0, Number(pollState?.finishedPolls ?? 0));
  const offset = polling.finishedOffsetsMs[completedPolls];
  return offset == null ? Number.POSITIVE_INFINITY : endedAt + offset;
}

export function nextDetailEligibility(game, pollState, config) {
  const lastDetailAt = timeOf(pollState?.lastDetailAt) ?? 0;
  const polling = pollingConfig(config);
  if (game.status === "LIVE") {
    return lastDetailAt + polling.liveDetailMs;
  }
  if (["DELAYED", "SUSPENDED", "POSTPONED"].includes(game.status)) {
    return lastDetailAt + polling.delayedDetailMs;
  }
  if (game.status === "FINISHED") {
    return nextFinishedEligibility(game, pollState, config);
  }
  return Number.POSITIVE_INFINITY;
}

export function selectDetailQueue(games, state, config, nowMs = Date.now()) {
  const maximum =
    config.run?.maxDetailGamesPerRun ?? 5;
  return games
    .map((game) => {
      const key = pollingKey(game);
      const pollState = state.polling?.[key] ?? {};
      return {
        game,
        key,
        pollState,
        eligibleAt: nextDetailEligibility(game, pollState, config),
      };
    })
    .filter((item) => item.eligibleAt <= nowMs)
    .sort((a, b) => {
      const aLast = timeOf(a.pollState.lastDetailAt) ?? 0;
      const bLast = timeOf(b.pollState.lastDetailAt) ?? 0;
      return aLast - bLast || a.key.localeCompare(b.key);
    })
    .slice(0, maximum);
}

export function recordDetailPoll(state, game, options = {}) {
  const key = pollingKey(game);
  const previous = state.polling?.[key] ?? {};
  state.polling ??= {};
  state.polling[key] = {
    ...previous,
    lastDetailAt: options.at ?? new Date().toISOString(),
    finishedPolls:
      game.status === "FINISHED"
        ? Number(previous.finishedPolls ?? 0) + 1
        : Number(previous.finishedPolls ?? 0),
    recordComplete: options.recordComplete ?? previous.recordComplete ?? false,
    lastError: options.error
      ? {
          code: options.error.code ?? "DETAIL_FETCH_FAILED",
          at: options.at ?? new Date().toISOString(),
        }
      : null,
  };
}
