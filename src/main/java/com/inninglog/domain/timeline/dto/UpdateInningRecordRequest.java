package com.inninglog.domain.timeline.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateInningRecordRequest(@NotNull @Size(max = 255) String text) {}
