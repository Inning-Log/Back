package com.inninglog.domain.game.model;

import java.time.Instant;

public record UserGameLog(long id, long userId, long gameId, long cheeringTeamId,
                          ViewingType viewingType, Instant createdAt, Instant updatedAt, Instant deletedAt) {}
