package com.inninglog.domain.oauth.controller;

import com.inninglog.domain.oauth.dto.GoogleLoginRequest;
import com.inninglog.domain.oauth.dto.LoginResponse;
import com.inninglog.domain.oauth.service.GoogleLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "OAuth 로그인", description = "외부 OAuth 공급자를 통한 회원가입 및 로그인 API")
public class GoogleOAuthController {

    private final GoogleLoginService googleLoginService;

    public GoogleOAuthController(GoogleLoginService googleLoginService) {
        this.googleLoginService = googleLoginService;
    }

    @Operation(summary = "Google ID 토큰 로그인",
            description = "Google Identity Services의 ID 토큰을 검증하고 신규 가입 또는 기존 계정 로그인을 처리합니다.")
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공. isNewUser로 신규 가입 여부 판단",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))),
            @ApiResponse(responseCode = "400", description = "credential이 비어 있음", content = @Content),
            @ApiResponse(responseCode = "401", description = "Google 토큰이 유효하지 않거나 이메일 인증이 완료되지 않음",
                    content = @Content(schema = @Schema(implementation = OAuthExceptionHandler.OAuthErrorResponse.class))),
            @ApiResponse(responseCode = "403", description = "이미 탈퇴 처리된 Google 계정임",
                    content = @Content(schema = @Schema(implementation = OAuthExceptionHandler.OAuthErrorResponse.class))),
            @ApiResponse(responseCode = "503", description = "Google OAuth 클라이언트 설정이 누락됨",
                    content = @Content(schema = @Schema(implementation = OAuthExceptionHandler.OAuthErrorResponse.class)))
    })
    @PostMapping("/google")
    public LoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return googleLoginService.loginWithGoogle(request.credential());
    }
}
