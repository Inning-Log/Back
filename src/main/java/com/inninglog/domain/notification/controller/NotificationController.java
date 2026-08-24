package com.inninglog.domain.notification.controller;

import com.inninglog.domain.notification.dto.PushRegistrationRequest;
import com.inninglog.domain.notification.dto.PushRegistrationResponse;
import com.inninglog.domain.notification.service.PushRegistrationService;
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

    private final PushRegistrationService pushRegistrationService;

    public NotificationController(PushRegistrationService pushRegistrationService) {
        this.pushRegistrationService = pushRegistrationService;
    }

    @Operation(
            summary = "FCM 앱 설치 등록",
            description = "FCM register/onRegistered로 받은 FID를 등록합니다. 같은 앱 설치에서 다른 계정에 로그인하면 소유권을 현재 사용자로 이전합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "FCM 앱 설치 등록 또는 갱신 성공",
                    content = @Content(schema = @Schema(implementation = PushRegistrationResponse.class))),
            @ApiResponse(responseCode = "400", description = "플랫폼 또는 FID 형식이 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = NotificationExceptionHandler.NotificationErrorResponse.class)))
    })
    @PutMapping("/push-registration")
    public PushRegistrationResponse registerPushRegistration(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody PushRegistrationRequest request
    ) {
        return pushRegistrationService.register(authentication.getName(), request);
    }

    @Operation(
            summary = "FCM 앱 설치 비활성화",
            description = "로그아웃 또는 알림 권한 해제 시 현재 사용자의 해당 FID를 비활성화합니다. 이미 없거나 비활성화된 경우도 성공합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "FCM 앱 설치 비활성화 완료"),
            @ApiResponse(responseCode = "400", description = "FID가 비어 있거나 허용 길이를 초과함", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = NotificationExceptionHandler.NotificationErrorResponse.class)))
    })
    @DeleteMapping("/push-registration")
    public ResponseEntity<Void> disablePushRegistration(
            JwtAuthenticationToken authentication,
            @Parameter(description = "등록한 Firebase Installation ID(FID)", example = "cVh7...installation-fid", required = true)
            @RequestParam @NotBlank @Size(max = 255) String installationId
    ) {
        pushRegistrationService.disable(authentication.getName(), installationId);
        return ResponseEntity.noContent().build();
    }
}
