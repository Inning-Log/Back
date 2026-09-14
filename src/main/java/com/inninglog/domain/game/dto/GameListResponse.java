package com.inninglog.domain.game.dto;

import com.inninglog.domain.game.repository.GameRepository.SyncState;
import java.time.LocalDate;
import java.util.List;

public record GameListResponse(LocalDate date, LocalDate today, SyncState scheduleData, List<GameResponse> games) {}
