package com.inninglog.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProfileSetupRequest(
        @NotBlank @Size(max = 80) String username,
        @NotBlank @Size(max = 80) String nickname
) {
}
