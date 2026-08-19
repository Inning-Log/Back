package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.config.AuthProperties;
import com.inninglog.domain.auth.dto.CurrentUserResponse;
import com.inninglog.domain.auth.dto.RefreshTokenRequest;
import com.inninglog.domain.auth.dto.TokenPairResponse;
import com.inninglog.domain.auth.service.AuthSessionService;
import com.inninglog.domain.user.dto.UserResponse;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "인증 세션", description = "서비스 JWT 발급 확인, 갱신 및 세션 폐기 API")
public class AuthSessionController {

    private final JwtTokenProvider jwtTokenProvider;
    private final AuthSessionService authSessionService;
    private final UserRepository userRepository;
    private final AuthProperties authProperties;

    public AuthSessionController(
            JwtTokenProvider jwtTokenProvider,
            AuthSessionService authSessionService,
            UserRepository userRepository,
            AuthProperties authProperties
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.authSessionService = authSessionService;
        this.userRepository = userRepository;
        this.authProperties = authProperties;
    }

    @Operation(
            summary = "개발용 JWT 발급",
            description = "로컬 개발 환경에서만 활성화되는 테스트용 JWT 발급 API입니다. 운영 환경에서는 404를 반환합니다."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "개발용 JWT 발급 성공",
                    content = @Content(schema = @Schema(implementation = JwtTokenProvider.IssuedToken.class))),
            @ApiResponse(responseCode = "400", description = "subject 또는 role 형식·길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "개발용 토큰 발급 기능이 비활성화됨", content = @Content)
    })
    @PostMapping("/dev-token")
    public ResponseEntity<JwtTokenProvider.IssuedToken> issueDevToken(
            @Valid @RequestBody DevTokenRequest request
    ) {
        if (!authProperties.devTokenEnabled()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jwtTokenProvider.issue(request.subject(), request.normalizedRoles()));
    }

    @Operation(
            summary = "서비스 토큰 갱신",
            description = "Refresh Token을 한 번만 사용할 수 있도록 회전하고 새 Access/Refresh Token 쌍을 반환합니다."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 회전 성공. 이전 Access/Refresh Token은 즉시 무효화됨",
                    content = @Content(schema = @Schema(implementation = TokenPairResponse.class))),
            @ApiResponse(responseCode = "400", description = "Refresh Token이 비어 있거나 허용 길이를 초과함", content = @Content),
            @ApiResponse(responseCode = "401", description = "Refresh Token이 만료·회전·폐기됐거나 탈퇴한 사용자의 토큰임",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class)))
    })
    @PostMapping("/refresh")
    public TokenPairResponse refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return TokenPairResponse.from(authSessionService.refresh(request.refreshToken()));
    }

    @Operation(summary = "현재 세션 로그아웃",
            description = "현재 Access Token이 속한 로그인 세션을 폐기합니다. 같은 세션의 Access/Refresh Token은 즉시 사용할 수 없습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "현재 세션 폐기 완료"),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 이미 무효화됨", content = @Content)
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(JwtAuthenticationToken authentication) {
        authSessionService.logout(authentication.getName(), sessionId(authentication));
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "모든 세션 로그아웃",
            description = "현재 사용자의 모든 로그인 세션을 폐기하여 모든 기기의 Access/Refresh Token을 즉시 무효화합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "전체 세션 폐기 완료"),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 이미 무효화됨", content = @Content),
            @ApiResponse(responseCode = "404", description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class)))
    })
    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(JwtAuthenticationToken authentication) {
        authSessionService.logoutAll(authentication.getName());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "현재 인증 사용자 조회",
            description = "JWT subject와 권한 목록, DB 사용자 정보를 반환합니다. 개발용 JWT처럼 DB 사용자가 없으면 user는 null입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "현재 인증 정보 조회 성공",
                    content = @Content(schema = @Schema(implementation = CurrentUserResponse.class))),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content)
    })
    @GetMapping("/me")
    public CurrentUserResponse me(JwtAuthenticationToken authentication) {
        return new CurrentUserResponse(
                authentication.getName(),
                authentication.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority)
                        .filter(Objects::nonNull)
                        .filter(authority -> authority.startsWith("ROLE_"))
                        .toList(),
                findUser(authentication.getName()));
    }

    private UserResponse findUser(String subject) {
        try {
            return userRepository.findByIdAndDeletedAtIsNull(Long.valueOf(subject))
                    .map(UserResponse::from)
                    .orElse(null);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Long sessionId(JwtAuthenticationToken authentication) {
        Object value = authentication.getToken().getClaim("sid");
        return value instanceof Number number ? number.longValue() : null;
    }

    @Schema(description = "개발용 JWT 발급 요청")
    public record DevTokenRequest(
            @Schema(description = "JWT subject", example = "1")
            @NotBlank @Size(max = 80) String subject,
            @Schema(description = "권한 목록. 생략 시 USER", example = "[\"USER\"]")
            Set<@NotBlank @Size(max = 40) String> roles
    ) {
        Collection<String> normalizedRoles() {
            if (roles == null || roles.isEmpty()) {
                return Set.of("USER");
            }
            return roles.stream()
                    .map(String::trim)
                    .filter(role -> !role.isBlank())
                    .map(String::toUpperCase)
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
