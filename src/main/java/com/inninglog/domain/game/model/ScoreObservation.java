package com.inninglog.domain.game.model;

import java.time.Instant;

/** A score seen in an earlier scoreboard response; not a scoring event timestamp. */
public record ScoreObservation(Instant observedAt, int homeScore, int awayScore) {}
