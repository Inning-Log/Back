package com.inninglog.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Refresh Token 갱신 요청")
public record RefreshTokenRequest(
        @Schema(description = "직전 로그인 또는 갱신에서 발급받은 Refresh Token")
        @NotBlank @Size(max = 512) String refreshToken
) {
}
