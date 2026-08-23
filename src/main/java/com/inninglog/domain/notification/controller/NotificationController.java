package com.inninglog.domain.notification.controller;

import com.inninglog.domain.notification.dto.PushTokenRegistrationRequest;
import com.inninglog.domain.notification.dto.PushTokenResponse;
import com.inninglog.domain.notification.service.PushTokenRegistrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
@Validated
@Tag(name = "알림", description = "현재 사용자의 푸시 수신 기기 관리 API")
public class NotificationController {

    private final PushTokenRegistrationService pushTokenRegistrationService;

    public NotificationController(PushTokenRegistrationService pushTokenRegistrationService) {
        this.pushTokenRegistrationService = pushTokenRegistrationService;
    }

    @Operation(
            summary = "푸시 토큰 등록",
            description = "현재 기기의 FCM 토큰을 등록하거나 갱신합니다. 같은 토큰으로 다른 계정에 로그인하면 소유권을 현재 사용자로 이전합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "푸시 토큰 등록 또는 갱신 성공",
                    content = @Content(schema = @Schema(implementation = PushTokenResponse.class))),
            @ApiResponse(responseCode = "400", description = "플랫폼, 기기 ID 또는 푸시 토큰 형식이 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = NotificationExceptionHandler.NotificationErrorResponse.class)))
    })
    @PutMapping("/push-token")
    public PushTokenResponse registerPushToken(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody PushTokenRegistrationRequest request
    ) {
        return pushTokenRegistrationService.register(authentication.getName(), request);
    }

    @Operation(
            summary = "푸시 토큰 비활성화",
            description = "로그아웃 또는 알림 권한 해제 시 현재 사용자의 해당 기기 푸시 토큰을 비활성화합니다. 이미 없거나 비활성화된 경우도 성공합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "푸시 토큰 비활성화 완료"),
            @ApiResponse(responseCode = "400", description = "기기 ID가 비어 있거나 허용 길이를 초과함", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = NotificationExceptionHandler.NotificationErrorResponse.class)))
    })
    @DeleteMapping("/push-token")
    public ResponseEntity<Void> disablePushToken(
            JwtAuthenticationToken authentication,
            @Parameter(description = "등록 시 사용한 기기 식별자", example = "installation-9f2a", required = true)
            @RequestParam @NotBlank @Size(max = 255) String deviceId
    ) {
        pushTokenRegistrationService.disable(authentication.getName(), deviceId);
        return ResponseEntity.noContent().build();
    }
}
