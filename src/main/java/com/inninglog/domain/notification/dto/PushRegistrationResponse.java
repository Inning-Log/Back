package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.entity.UserPushRegistration;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "등록된 FCM 앱 설치 정보")
public record PushRegistrationResponse(
        Long id,
        DevicePlatform platform,
        String installationId,
        boolean enabled,
        Instant lastSeenAt
) {

    public static PushRegistrationResponse from(UserPushRegistration registration) {
        return new PushRegistrationResponse(
                registration.getId(),
                registration.getPlatform(),
                registration.getInstallationId(),
                registration.isEnabled(),
                registration.getLastSeenAt());
    }
}
