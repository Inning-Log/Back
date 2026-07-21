package com.inninglog.domain.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FavoriteTeamSelectionRequest(
        @NotNull @Positive Long favoriteTeamId
) {
}
