package com.inninglog.domain.home.dto;

import com.inninglog.domain.game.dto.GameResponse;
import com.inninglog.domain.game.repository.GameRepository.SyncState;
import java.time.LocalDate;
import java.util.List;

public record CalendarResponse(int year, int month, String timezone, LocalDate today,
                               long favoriteTeamId, SyncState scheduleData, List<Day> days) {
    public record Day(LocalDate date, List<GameResponse> games) {}
}
