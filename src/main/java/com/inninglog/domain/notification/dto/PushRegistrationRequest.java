package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "현재 앱 설치의 FCM 등록 또는 갱신 요청")
public record PushRegistrationRequest(
        @Schema(description = "클라이언트 플랫폼. Android에 설치한 PWA도 WEB", example = "WEB",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull DevicePlatform platform,

        @Schema(description = "FCM register/onRegistered 콜백으로 받은 Firebase Installation ID(FID)",
                example = "cVh7...installation-fid",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 255) String installationId
) {
}
