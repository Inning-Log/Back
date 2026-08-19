package com.inninglog.domain.user.controller;

import com.inninglog.domain.user.service.AccountDeletionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@Tag(name = "계정", description = "현재 사용자 계정 생명주기 API")
public class AccountController {

    private final AccountDeletionService accountDeletionService;

    public AccountController(AccountDeletionService accountDeletionService) {
        this.accountDeletionService = accountDeletionService;
    }

    @Operation(
            summary = "회원 탈퇴",
            description = "사용자를 소프트 삭제하고 개인정보를 비식별화한 뒤 모든 로그인 세션을 한 트랜잭션에서 폐기합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "탈퇴 처리 완료"),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 이미 무효화됨", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = UserExceptionHandler.UserErrorResponse.class))
            )
    })
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(JwtAuthenticationToken authentication) {
        accountDeletionService.deleteCurrentUser(authentication.getName());
        return ResponseEntity.noContent().build();
    }
}
