package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.dto.FavoriteTeamSelectionRequest;
import com.inninglog.domain.auth.dto.NicknameSetupRequest;
import com.inninglog.domain.auth.dto.OnboardingStatusResponse;
import com.inninglog.domain.auth.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.auth.dto.UsernameSetupRequest;
import com.inninglog.domain.auth.service.ProfileSetupService;
import com.inninglog.domain.auth.service.UsernamePolicy;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
@Validated
@Tag(name = "온보딩", description = "신규 사용자의 아이디·닉네임·응원팀 설정 API")
public class OnboardingController {

    private final ProfileSetupService profileSetupService;

    public OnboardingController(ProfileSetupService profileSetupService) {
        this.profileSetupService = profileSetupService;
    }

    @Operation(
            summary = "온보딩 진행 상태 조회",
            description = "현재 사용자 정보에 따라 다음 진행 단계(USERNAME, NICKNAME, FAVORITE_TEAM, COMPLETED)를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "현재 단계 조회 성공",
                    content = @Content(schema = @Schema(implementation = OnboardingStatusResponse.class))
            ),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @GetMapping
    public OnboardingStatusResponse getStatus(JwtAuthenticationToken authentication) {
        return profileSetupService.getOnboardingStatus(authentication.getName());
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
    @GetMapping("/username-availability")
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
            summary = "온보딩 아이디 설정",
            description = "아이디를 소문자로 정규화하여 저장하고 다음 온보딩 단계를 반환합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "아이디 저장 성공. nextStep은 NICKNAME",
                    content = @Content(schema = @Schema(implementation = OnboardingStatusResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "아이디가 비어 있거나 형식·길이가 유효하지 않음", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "이미 사용 중인 아이디이거나 온보딩을 이미 완료함",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PutMapping("/username")
    public OnboardingStatusResponse setupUsername(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody UsernameSetupRequest request
    ) {
        return profileSetupService.setupUsername(authentication.getName(), request.username());
    }

    @Operation(
            summary = "온보딩 닉네임 설정",
            description = "닉네임을 저장하고 응원팀 선택 단계로 이동합니다. 아이디 설정을 먼저 완료해야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "닉네임 저장 성공. nextStep은 FAVORITE_TEAM",
                    content = @Content(schema = @Schema(implementation = OnboardingStatusResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "닉네임이 비어 있거나 80자를 초과함", content = @Content),
            @ApiResponse(responseCode = "401", description = "JWT가 없거나 유효하지 않음", content = @Content),
            @ApiResponse(
                    responseCode = "404",
                    description = "JWT의 사용자 정보가 DB에 존재하지 않음",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            ),
            @ApiResponse(
                    responseCode = "409",
                    description = "아이디 설정이 선행되지 않았거나 온보딩을 이미 완료함",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PutMapping("/nickname")
    public OnboardingStatusResponse setupNickname(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody NicknameSetupRequest request
    ) {
        return profileSetupService.setupNickname(authentication.getName(), request.nickname());
    }

    @Operation(
            summary = "최초 응원팀 선택 및 온보딩 완료",
            description = "활성 KBO 팀을 최초 응원팀으로 저장하고 온보딩을 완료합니다. 아이디와 닉네임 설정이 선행되어야 합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "응원팀 저장 및 온보딩 완료. nextStep은 COMPLETED",
                    content = @Content(schema = @Schema(implementation = OnboardingStatusResponse.class))
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
                    description = "아이디·닉네임 설정이 선행되지 않았거나 온보딩을 이미 완료함",
                    content = @Content(schema = @Schema(implementation = AuthExceptionHandler.AuthErrorResponse.class))
            )
    })
    @PutMapping("/favorite-team")
    public OnboardingStatusResponse selectFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamSelectionRequest request
    ) {
        return profileSetupService.completeOnboarding(authentication.getName(), request.favoriteTeamId());
    }
}
