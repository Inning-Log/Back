package com.inninglog.domain.notification.controller;

import com.inninglog.domain.notification.dto.NotificationSettingsResponse;
import com.inninglog.domain.notification.dto.NotificationSettingsUpdateRequest;
import com.inninglog.domain.notification.service.NotificationSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notification-settings")
@Tag(name = "알림 설정", description = "현재 사용자의 푸시 알림 수신 설정 API")
public class NotificationSettingsController {

    private final NotificationSettingsService settingsService;

    public NotificationSettingsController(NotificationSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @Operation(summary = "알림 설정 조회", description = "저장된 설정이 없으면 모든 선택 항목을 활성화해 반환합니다.")
    @GetMapping
    public NotificationSettingsResponse getSettings(JwtAuthenticationToken authentication) {
        return settingsService.get(authentication.getName());
    }

    @Operation(summary = "알림 설정 변경", description = "전달한 필드만 변경합니다.")
    @PatchMapping
    public NotificationSettingsResponse updateSettings(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody NotificationSettingsUpdateRequest request
    ) {
        return settingsService.update(authentication.getName(), request);
    }
}
