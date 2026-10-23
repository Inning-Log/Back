package com.inninglog.domain.mypage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ProfileImageUploadRequest(@NotBlank String contentType, @NotNull @Positive Long contentLength) {}

