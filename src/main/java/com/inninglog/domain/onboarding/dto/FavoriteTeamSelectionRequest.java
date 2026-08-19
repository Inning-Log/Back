package com.inninglog.domain.onboarding.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FavoriteTeamSelectionRequest(
        @NotNull @Positive Long favoriteTeamId
) {
}
