package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.Game;
import com.inninglog.domain.game.model.GameResult;
import com.inninglog.domain.game.model.UserGameLog;
import com.inninglog.domain.game.model.ViewingType;
import java.time.Instant;

public record UserGameLogResponse(long id, long gameId, long cheeringTeamId, ViewingType viewingType,
                                  GameResult result, Instant createdAt, Instant updatedAt) {
    public static UserGameLogResponse from(UserGameLog log, Game game) {
        return new UserGameLogResponse(log.id(), log.gameId(), log.cheeringTeamId(), log.viewingType(),
                game.resultFor(log.cheeringTeamId()), log.createdAt(), log.updatedAt());
    }
}
