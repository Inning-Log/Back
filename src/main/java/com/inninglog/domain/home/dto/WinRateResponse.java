package com.inninglog.domain.home.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WinRateResponse(int seasonYear, List<String> gameTypes, String viewingType,
                              long registeredCount, long wins, long losses, long draws,
                              long pendingCount, long voidCount, long unknownCount,
                              long unclassifiedCount, long decidedCount,
                              BigDecimal winRatePercent, Instant calculatedAt) {}
