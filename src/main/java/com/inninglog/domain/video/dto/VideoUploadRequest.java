package com.inninglog.domain.video.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record VideoUploadRequest(@NotBlank @Size(max = 100) String contentType, @NotNull @Positive Long contentLength) {}
