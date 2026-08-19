package com.inninglog.domain.onboarding.controller;

import com.inninglog.domain.onboarding.dto.FavoriteTeamSelectionRequest;
import com.inninglog.domain.onboarding.dto.ProfileSetupRequest;
import com.inninglog.domain.onboarding.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.onboarding.service.ProfileSetupService;
import com.inninglog.domain.user.dto.UserResponse;
import com.inninglog.domain.user.service.UsernamePolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
@RequestMapping("/api/auth/profile")
@Validated
@Tag(name = "온보딩 호환 API", description = "구버전 클라이언트를 위한 일괄 프로필 온보딩 API")
public class LegacyOnboardingController {

    private final ProfileSetupService profileSetupService;

    public LegacyOnboardingController(ProfileSetupService profileSetupService) {
        this.profileSetupService = profileSetupService;
    }

    @Operation(summary = "아이디와 닉네임 일괄 설정",
            description = "아이디와 닉네임을 한 요청으로 저장합니다. 신규 클라이언트는 단계별 /api/onboarding API 사용을 권장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "아이디와 닉네임 저장 성공",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "아이디 또는 닉네임 형식이 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = OnboardingExceptionHandler.OnboardingErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "아이디가 이미 사용 중임",
                    content = @Content(schema = @Schema(implementation = OnboardingExceptionHandler.OnboardingErrorResponse.class)))
    })
    @PutMapping
    public UserResponse setupProfile(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileSetupRequest request
    ) {
        return profileSetupService.setup(authentication.getName(), request.username(), request.nickname());
    }

    @Operation(summary = "최초 응원팀 선택",
            description = "최초 응원팀을 저장하고 온보딩을 완료합니다. 단계별 API와의 하위 호환용입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "응원팀 저장 및 온보딩 완료",
                    content = @Content(schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "응원팀 ID가 없거나 양수가 아님", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "사용자 또는 활성 응원팀을 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = OnboardingExceptionHandler.OnboardingErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "프로필 설정이 선행되지 않았거나 응원팀을 이미 선택함",
                    content = @Content(schema = @Schema(implementation = OnboardingExceptionHandler.OnboardingErrorResponse.class)))
    })
    @PostMapping("/favorite-team")
    public UserResponse selectInitialFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamSelectionRequest request
    ) {
        return profileSetupService.selectInitialFavoriteTeam(authentication.getName(), request.favoriteTeamId());
    }

    @Operation(summary = "아이디 사용 가능 여부 확인",
            description = "입력값을 소문자로 정규화한 뒤 중복 여부를 확인합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "중복 확인 성공",
                    content = @Content(schema = @Schema(implementation = UsernameAvailabilityResponse.class))),
            @ApiResponse(responseCode = "400", description = "아이디 형식이 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
                    content = @Content(schema = @Schema(implementation = OnboardingExceptionHandler.OnboardingErrorResponse.class)))
    })
    @GetMapping("/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @Parameter(description = "@를 제외한 아이디", example = "inning.log", required = true)
            @RequestParam @NotBlank @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN) String username
    ) {
        return profileSetupService.checkUsernameAvailability(authentication.getName(), username);
    }
}
