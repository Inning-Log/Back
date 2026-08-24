package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.DevicePlatform;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "현재 기기의 푸시 토큰 등록 또는 갱신 요청")
public record PushTokenRegistrationRequest(
        @Schema(description = "기기 플랫폼", example = "ANDROID", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull DevicePlatform platform,

        @Schema(description = "Firebase Installations getId()로 얻은 앱 설치 단위 FID", example = "cVh7...installation-fid",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 255) String deviceId,

        @Schema(description = "FirebaseMessaging getToken()으로 얻은 FCM registration token", example = "fcm-registration-token",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank @Size(max = 500) String pushToken
) {
}
