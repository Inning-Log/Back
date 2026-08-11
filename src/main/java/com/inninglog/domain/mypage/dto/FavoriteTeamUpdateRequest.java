package com.inninglog.domain.mypage.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record FavoriteTeamUpdateRequest(
        @NotNull @Positive Long favoriteTeamId
) {
}
