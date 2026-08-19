package com.inninglog.domain.onboarding.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameSetupRequest(
        @NotBlank @Size(max = 80) String nickname
) {
}
