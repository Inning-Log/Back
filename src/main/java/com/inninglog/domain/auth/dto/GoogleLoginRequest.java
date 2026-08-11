package com.inninglog.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Google 로그인 요청")
public record GoogleLoginRequest(
        @Schema(description = "Google Identity Services가 발급한 ID 토큰", example = "eyJhbGciOiJSUzI1NiIs...")
        @NotBlank String credential
) {
}
