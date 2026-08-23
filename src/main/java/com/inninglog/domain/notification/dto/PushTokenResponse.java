package com.inninglog.domain.notification.dto;

import com.inninglog.domain.notification.entity.DevicePlatform;
import com.inninglog.domain.notification.entity.UserPushToken;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "등록된 푸시 토큰 정보")
public record PushTokenResponse(
        Long id,
        DevicePlatform platform,
        String deviceId,
        boolean enabled,
        Instant lastSeenAt
) {

    public static PushTokenResponse from(UserPushToken token) {
        return new PushTokenResponse(
                token.getId(),
                token.getPlatform(),
                token.getDeviceId(),
                token.isEnabled(),
                token.getLastSeenAt());
    }
}
