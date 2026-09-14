package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.ViewingType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateUserGameLogRequest(@NotNull @Positive Long gameId,
                                       @NotNull @Positive Long cheeringTeamId,
                                       @NotNull ViewingType viewingType) {}
