package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.model.ViewingType;
import jakarta.validation.constraints.NotNull;

public record UpdateUserGameLogRequest(@NotNull ViewingType viewingType) {}
