package com.inninglog.domain.onboarding.dto;

import com.inninglog.domain.user.service.UsernamePolicy;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProfileSetupRequest(
        @NotBlank
        @Size(max = UsernamePolicy.MAX_LENGTH)
        @Pattern(regexp = UsernamePolicy.PATTERN)
        String username,
        @NotBlank @Size(max = 80) String nickname
) {
}
