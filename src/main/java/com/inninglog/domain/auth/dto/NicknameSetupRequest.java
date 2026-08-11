package com.inninglog.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NicknameSetupRequest(
        @NotBlank @Size(max = 80) String nickname
) {
}
