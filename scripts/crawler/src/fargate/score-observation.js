// Observation times bound changes in the source page, not the moment a run scored.
// Reuse the existing scoreboard response; this must never trigger another request.
export function attachPreviousScoreObservation(snapshot, previous) {
  for (const game of snapshot.games ?? []) {
    if (!game.meta) continue;
    delete game.meta.previousScoreObservation;
    const id = game.externalId?.kbo;
    const matches = (previous?.games ?? []).filter(old => id && old.externalId?.kbo === id
      && old.date === game.date && old.homeTeam?.code === game.homeTeam?.code
      && old.awayTeam?.code === game.awayTeam?.code);
    if (matches.length !== 1 || !scoredObservation(game)) continue;
    const old = matches[0];
    if (!scoredObservation(old)
      || Date.parse(old.meta.resultObservedAt) >= Date.parse(game.meta.resultObservedAt)) continue;
    game.meta.previousScoreObservation = {
      observedAt: old.meta.resultObservedAt,
      homeScore: old.score.home,
      awayScore: old.score.away,
    };
  }
  return snapshot;
}

function scoredObservation(game) {
  return game.meta?.resultSource === "SCOREBOARD"
    && Number.isFinite(Date.parse(game.meta.resultObservedAt))
    && Number.isInteger(game.score?.home) && game.score.home >= 0
    && Number.isInteger(game.score?.away) && game.score.away >= 0;
}
