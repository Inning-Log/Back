package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.dto.CurrentUserResponse;
import com.inninglog.domain.auth.dto.FavoriteTeamSelectionRequest;
import com.inninglog.domain.auth.dto.GoogleLoginRequest;
import com.inninglog.domain.auth.dto.LoginResponse;
import com.inninglog.domain.auth.dto.ProfileSetupRequest;
import com.inninglog.domain.auth.dto.UserResponse;
import com.inninglog.domain.auth.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.auth.service.OAuthLoginService;
import com.inninglog.domain.auth.service.ProfileSetupService;
import com.inninglog.domain.auth.service.UsernamePolicy;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@Validated
@Tag(name = "인증", description = "Google 로그인, JWT 확인 및 기존 프로필 설정 API")
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final OAuthLoginService oAuthLoginService;
    private final ProfileSetupService profileSetupService;
    private final UserRepository userRepository;
    private final boolean devTokenEnabled;

    public AuthController(
            JwtTokenProvider jwtTokenProvider,
            OAuthLoginService oAuthLoginService,
            ProfileSetupService profileSetupService,
            UserRepository userRepository,
            @Value("${app.auth.dev-token-enabled:false}") boolean devTokenEnabled
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.oAuthLoginService = oAuthLoginService;
        this.profileSetupService = profileSetupService;
        this.userRepository = userRepository;
        this.devTokenEnabled = devTokenEnabled;
    }

    @Operation(
            summary = "개발용 JWT 발급",
            description = "로컬 개발 환경에서만 활성화되는 테스트용 JWT 발급 API입니다. 운영 환경에서는 404를 반환합니다."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "개발용 JWT 발급 성공",
                    content = @Content(schema = @Schema(implementation = JwtTokenProvider.IssuedToken.class))
            ),
            @ApiResponse(responseCode = "400", description = "subject 또는 role 형식·길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "개발용 토큰 발급 기능이 비활성화됨", content = @Content)
    })
    @PostMapping("/dev-token")
    public ResponseEntity<JwtTokenProvider.IssuedToken> issueDevToken(
            @Valid @RequestBody DevTokenRequest request
    ) {
        if (!devTokenEnabled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jwtTokenProvider.issue(request.subject(), request.normalizedRoles()));
    }

    @Operation(
            summary = "Google ID 토큰 로그인",
            description = "Google Identity Services가 발급한 ID 토큰을 검증하고 서비스 JWT와 사용자 정보를 반환합니다."
    )
    @SecurityRequirements
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "로그인 성공. isNewUser로 신규 가입 여부 판단",
                    content = @Content(schema = @Schema(implementation = LoginResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "credential이 비어 있음", content = @Content),
            @ApiResponse(
                    responseCode = "401",
                    description = "Google 토큰이 유효하지 않거나 이메일 인증이 완료되지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "Google OAuth 클라이언트 설정이 누락됨",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PostMapping("/google")
    public LoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return oAuthLoginService.loginWithGoogle(request.credential());
    }

    @Operation(
            summary = "아이디와 닉네임 설정",
            description = "아이디를 소문자로 정규화하여 닉네임과 함께 저장합니다. 단계별 온보딩 API와의 하위 호환용 엔드포인트입니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "아이디와 닉네임 저장 성공",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "아이디 또는 닉네임의 형식·길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "아이디가 이미 사용 중임",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PutMapping("/profile")
    public UserResponse setupProfile(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileSetupRequest request
    ) {
        return profileSetupService.setup(
                authentication.getName(),
                request.username(),
                request.nickname());
    }

    @Operation(
            summary = "최초 응원팀 선택",
            description = "최초 응원팀을 저장하고 온보딩을 완료합니다. 단계별 온보딩 API와의 하위 호환용 엔드포인트입니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "응원팀 저장 및 온보딩 완료",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "응원팀 ID가 없거나 양수가 아님", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "사용자 또는 활성 응원팀을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "아이디·닉네임 설정이 선행되지 않았거나 최초 응원팀을 이미 선택함",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PostMapping("/profile/favorite-team")
    public UserResponse selectInitialFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamSelectionRequest request
    ) {
        return profileSetupService.selectInitialFavoriteTeam(
                authentication.getName(),
                request.favoriteTeamId());
    }

    @Operation(
            summary = "아이디 사용 가능 여부 확인",
            description = "입력값을 소문자로 정규화한 뒤 중복 여부를 확인합니다. 현재 사용자의 기존 아이디는 사용 가능으로 판단합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "중복 확인 성공. available 값으로 사용 가능 여부 판단",
                    content = @Content(schema = @Schema(implementation = UsernameAvailabilityResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "아이디 형식 또는 길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @GetMapping("/profile/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @Parameter(description = "@를 제외한 아이디", example = "inning.log", required = true)
            @RequestParam
            @NotBlank
            @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN)
            String username
    ) {
        return profileSetupService.checkUsernameAvailability(authentication.getName(), username);
    }

    @Operation(
            summary = "현재 인증 사용자 조회",
            description = "JWT subject와 권한 목록, DB 사용자 정보를 반환합니다. 개발용 JWT처럼 DB 사용자가 없으면 user는 null입니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "현재 인증 정보 조회 성공",
                    content = @Content(schema = @Schema(implementation = CurrentUserResponse.class))
            ),
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
            Long userId = Long.valueOf(subject);
            return userRepository.findById(userId)
                    .map(UserResponse::from)
                    .orElse(null);
        } catch (NumberFormatException exception) {
            return null;
        }
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
