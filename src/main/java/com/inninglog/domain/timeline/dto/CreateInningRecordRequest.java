package com.inninglog.domain.timeline.dto;

import com.inninglog.domain.timeline.model.InningHalf;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public record CreateInningRecordRequest(
        @NotNull @Positive Long gameId,
        @NotNull UUID clientRecordId,
        @NotNull @Min(1) @Max(99) Integer inning,
        InningHalf half,
        @NotNull Instant recordedAt,
        @NotNull @Size(max = 255) String text
) {}
