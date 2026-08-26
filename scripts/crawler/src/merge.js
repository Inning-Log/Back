import {
  applyGamePatch,
  normalizeGameSnapshot,
  teamKey,
} from "./domain.js";

/** Stable date/home/away key. Scheduled time is evaluated separately. */
export function makeMatchKey(game) {
  const date = game?.date ?? null;
  const home = teamKey(game?.homeTeam);
  const away = teamKey(game?.awayTeam);
  return date && home && away ? `${date}|${home}|${away}` : null;
}

/** Absolute scheduled-time distance, in minutes, or null when either side lacks it. */
export function scheduledTimeDistanceMinutes(left, right) {
  if (!left?.scheduledAt || !right?.scheduledAt) return null;
  const leftMillis = Date.parse(left.scheduledAt);
  const rightMillis = Date.parse(right.scheduledAt);
  if (Number.isNaN(leftMillis) || Number.isNaN(rightMillis)) return null;
  return Math.abs(leftMillis - rightMillis) / 60_000;
}

/**
 * Match two provider collections one-to-one. Ambiguous assignments are not
 * guessed: invariant pairs may be retained while uncertain pairs are returned
 * as `AMBIGUOUS_MATCH` anomalies.
 */
export function matchHybridGames(leftGames = [], rightGames = [], options = {}) {
  const settings = {
    leftSource: options.leftSource ?? "kbo",
    rightSource: options.rightSource ?? "naver",
    timeToleranceMinutes: finiteOr(options.timeToleranceMinutes, 120),
    ambiguityWindowMinutes: finiteOr(options.ambiguityWindowMinutes, 5),
    missingOneTimeCost: finiteOr(options.missingOneTimeCost, 720),
    missingBothTimesCost: finiteOr(options.missingBothTimesCost, 1_440),
    maxGroupSize: Math.max(1, Math.trunc(finiteOr(options.maxGroupSize, 8))),
    timezoneOffsetMinutes: options.timezoneOffsetMinutes,
  };

  const left = leftGames.map((game) => normalizeGameSnapshot(game, {
    source: settings.leftSource,
    timezoneOffsetMinutes: settings.timezoneOffsetMinutes,
  }));
  const right = rightGames.map((game) => normalizeGameSnapshot(game, {
    source: settings.rightSource,
    timezoneOffsetMinutes: settings.timezoneOffsetMinutes,
  }));
  const groups = new Map();
  const anomalies = [];

  addToGroups(left, "left", groups, anomalies);
  addToGroups(right, "right", groups, anomalies);

  const matches = [];
  for (const [key, group] of groups) {
    if (group.left.length === 0 || group.right.length === 0) continue;
    if (Math.max(group.left.length, group.right.length) > settings.maxGroupSize) {
      anomalies.push({
        type: "MATCH_GROUP_TOO_LARGE",
        key,
        leftIndexes: group.left.map((item) => item.index),
        rightIndexes: group.right.map((item) => item.index),
        message: `match group exceeds safe limit ${settings.maxGroupSize}`,
      });
      continue;
    }

    const solved = solveGroup(key, group, settings);
    matches.push(...solved.matches);
    anomalies.push(...solved.anomalies);
  }

  matches.sort((a, b) => a.leftIndex - b.leftIndex || a.rightIndex - b.rightIndex);
  const usedLeft = new Set(matches.map((match) => match.leftIndex));
  const usedRight = new Set(matches.map((match) => match.rightIndex));
  return {
    matches,
    anomalies,
    unmatched: {
      left: left.map((_, index) => index).filter((index) => !usedLeft.has(index)),
      right: right.map((_, index) => index).filter((index) => !usedRight.has(index)),
    },
  };
}

/**
 * Normalize, match, and conservatively merge KBO/Naver records.
 * Right-side data patches left-side data, while null/UNKNOWN values cannot erase it.
 */
export function mergeHybridGames(leftGames = [], rightGames = [], options = {}) {
  const leftSource = options.leftSource ?? "kbo";
  const rightSource = options.rightSource ?? "naver";
  const domainOptions = { timezoneOffsetMinutes: options.timezoneOffsetMinutes };
  const left = leftGames.map((game) => normalizeGameSnapshot(game, { ...domainOptions, source: leftSource }));
  const right = rightGames.map((game) => normalizeGameSnapshot(game, { ...domainOptions, source: rightSource }));
  const result = matchHybridGames(left, right, { ...options, leftSource, rightSource });
  const rightByLeft = new Map(result.matches.map((match) => [match.leftIndex, match]));
  const games = [];

  for (let leftIndex = 0; leftIndex < left.length; leftIndex += 1) {
    const match = rightByLeft.get(leftIndex);
    if (!match) {
      games.push(left[leftIndex]);
      continue;
    }
    const merged = applyGamePatch(left[leftIndex], right[match.rightIndex], domainOptions);
    merged.meta = {
      ...merged.meta,
      hybridMatch: {
        key: match.key,
        by: match.by,
        timeDeltaMinutes: match.timeDeltaMinutes,
        leftIndex,
        rightIndex: match.rightIndex,
      },
    };
    games.push(merged);
  }

  const matchedRight = new Set(result.matches.map((match) => match.rightIndex));
  for (let rightIndex = 0; rightIndex < right.length; rightIndex += 1) {
    if (!matchedRight.has(rightIndex)) games.push(right[rightIndex]);
  }

  return { games, ...result };
}

// Compatibility-friendly short name for callers migrating from the old crawler helper.
export const mergeHybrid = mergeHybridGames;

function addToGroups(games, side, groups, anomalies) {
  for (let index = 0; index < games.length; index += 1) {
    const game = games[index];
    const key = makeMatchKey(game);
    if (!key) {
      anomalies.push({
        type: "INVALID_MATCH_KEY",
        side,
        index,
        message: "date, home team, and away team are required for hybrid matching",
      });
      continue;
    }
    const group = groups.get(key) ?? { left: [], right: [] };
    group[side].push({ index, game });
    groups.set(key, group);
  }
}

function solveGroup(key, group, settings) {
  const edgesByLeft = new Map();
  const candidateSummary = [];
  for (let localLeft = 0; localLeft < group.left.length; localLeft += 1) {
    const edges = [];
    for (let localRight = 0; localRight < group.right.length; localRight += 1) {
      const edge = makeEdge(group.left[localLeft], group.right[localRight], localLeft, localRight, settings);
      candidateSummary.push(edge.summary);
      if (edge.allowed) edges.push(edge);
    }
    edgesByLeft.set(localLeft, edges);
  }

  const assignments = [];
  enumerateAssignments(0, group.left.length, edgesByLeft, new Set(), [], 0, assignments);
  assignments.sort((a, b) => b.pairs.length - a.pairs.length || a.cost - b.cost || assignmentSignature(a).localeCompare(assignmentSignature(b)));
  const best = assignments[0] ?? { pairs: [], cost: 0 };

  if (best.pairs.length === 0) {
    const hasRejectedTime = candidateSummary.some((candidate) => candidate.rejectedReason === "TIME_TOLERANCE");
    const hasGameNumberConflict = candidateSummary.some((candidate) => candidate.rejectedReason === "GAME_NUMBER_CONFLICT");
    const anomaly = hasRejectedTime
      ? {
          type: "TIME_MISMATCH",
          message: `no scheduled time pair is within ${settings.timeToleranceMinutes} minutes`,
        }
      : hasGameNumberConflict
        ? {
            type: "GAME_NUMBER_MISMATCH",
            message: "explicit doubleheader game numbers conflict",
          }
        : null;
    return {
      matches: [],
      anomalies: anomaly ? [{
        ...anomaly,
        key,
        leftIndexes: group.left.map((item) => item.index),
        rightIndexes: group.right.map((item) => item.index),
        candidates: candidateSummary,
      }] : [],
    };
  }

  const contenders = assignments.filter((assignment) =>
    assignment.pairs.length === best.pairs.length
    && assignment.cost <= best.cost + settings.ambiguityWindowMinutes
  );
  if (contenders.length === 1) {
    return { matches: best.pairs.map((pair) => formatMatch(key, pair, group)), anomalies: [] };
  }

  const common = commonPairSignatures(contenders);
  const commonPairs = best.pairs.filter((pair) => common.has(pairSignature(pair)));
  const ambiguousLeft = new Set();
  const ambiguousRight = new Set();
  for (const assignment of contenders) {
    for (const pair of assignment.pairs) {
      if (!common.has(pairSignature(pair))) {
        ambiguousLeft.add(group.left[pair.localLeft].index);
        ambiguousRight.add(group.right[pair.localRight].index);
      }
    }
  }

  return {
    matches: commonPairs.map((pair) => formatMatch(key, pair, group)),
    anomalies: [{
      type: "AMBIGUOUS_MATCH",
      key,
      leftIndexes: [...ambiguousLeft].sort((a, b) => a - b),
      rightIndexes: [...ambiguousRight].sort((a, b) => a - b),
      candidates: candidateSummary.filter((candidate) =>
        ambiguousLeft.has(candidate.leftIndex) && ambiguousRight.has(candidate.rightIndex)
      ),
      competingAssignments: contenders.map((assignment) => ({
        cost: assignment.cost,
        pairs: assignment.pairs.map((pair) => ({
          leftIndex: group.left[pair.localLeft].index,
          rightIndex: group.right[pair.localRight].index,
        })),
      })),
      message: "multiple one-to-one assignments are equally plausible; uncertain games were not merged",
    }],
  };
}

function makeEdge(leftItem, rightItem, localLeft, localRight, settings) {
  const left = leftItem.game;
  const right = rightItem.game;
  const sameExternalId = hasSharedExternalId(left, right);
  const sameGameNumber = left.gameNumber !== null
    && right.gameNumber !== null
    && left.gameNumber === right.gameNumber;
  const conflictingGameNumber = left.gameNumber !== null
    && right.gameNumber !== null
    && left.gameNumber !== right.gameNumber;
  const timeDeltaMinutes = scheduledTimeDistanceMinutes(left, right);
  const timeRejected = timeDeltaMinutes !== null
    && timeDeltaMinutes > settings.timeToleranceMinutes
    && !sameExternalId
    && !sameGameNumber;
  const allowed = !conflictingGameNumber && !timeRejected;
  let cost;
  if (timeDeltaMinutes !== null) cost = timeDeltaMinutes;
  else if (left.scheduledAt || right.scheduledAt) cost = settings.missingOneTimeCost;
  else cost = settings.missingBothTimesCost;
  if (sameGameNumber) cost -= 10_000;
  if (sameExternalId) cost -= 100_000;

  const by = sameExternalId
    ? "EXTERNAL_ID"
    : sameGameNumber
      ? "GAME_NUMBER"
      : timeDeltaMinutes !== null
        ? "SCHEDULED_AT"
        : "UNIQUE_TEAM_DATE";
  return {
    allowed,
    localLeft,
    localRight,
    cost,
    by,
    timeDeltaMinutes,
    summary: {
      leftIndex: leftItem.index,
      rightIndex: rightItem.index,
      gameNumber: { left: left.gameNumber, right: right.gameNumber },
      timeDeltaMinutes,
      allowed,
      rejectedReason: conflictingGameNumber ? "GAME_NUMBER_CONFLICT" : timeRejected ? "TIME_TOLERANCE" : null,
    },
  };
}

function enumerateAssignments(localLeft, leftCount, edgesByLeft, usedRight, pairs, cost, output) {
  if (localLeft >= leftCount) {
    output.push({ pairs: [...pairs], cost });
    return;
  }

  // Leaving a record unmatched is valid; ranking later maximizes pair count first.
  enumerateAssignments(localLeft + 1, leftCount, edgesByLeft, usedRight, pairs, cost, output);
  for (const edge of edgesByLeft.get(localLeft) ?? []) {
    if (usedRight.has(edge.localRight)) continue;
    usedRight.add(edge.localRight);
    pairs.push(edge);
    enumerateAssignments(localLeft + 1, leftCount, edgesByLeft, usedRight, pairs, cost + edge.cost, output);
    pairs.pop();
    usedRight.delete(edge.localRight);
  }
}

function formatMatch(key, pair, group) {
  return {
    key,
    leftIndex: group.left[pair.localLeft].index,
    rightIndex: group.right[pair.localRight].index,
    by: pair.by,
    timeDeltaMinutes: pair.timeDeltaMinutes,
  };
}

function commonPairSignatures(assignments) {
  const [first, ...rest] = assignments;
  const common = new Set((first?.pairs ?? []).map(pairSignature));
  for (const assignment of rest) {
    const current = new Set(assignment.pairs.map(pairSignature));
    for (const signature of common) if (!current.has(signature)) common.delete(signature);
  }
  return common;
}

function pairSignature(pair) {
  return `${pair.localLeft}:${pair.localRight}`;
}

function assignmentSignature(assignment) {
  return assignment.pairs.map(pairSignature).sort().join(",");
}

function hasSharedExternalId(left, right) {
  return ["kbo", "naver"].some((provider) =>
    left.externalId?.[provider]
    && right.externalId?.[provider]
    && left.externalId[provider] === right.externalId[provider]
  );
}

function finiteOr(value, fallback) {
  return Number.isFinite(value) && value >= 0 ? value : fallback;
}
