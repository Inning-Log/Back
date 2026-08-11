package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.dto.FavoriteTeamSelectionRequest;
import com.inninglog.domain.auth.dto.NicknameSetupRequest;
import com.inninglog.domain.auth.dto.OnboardingStatusResponse;
import com.inninglog.domain.auth.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.auth.dto.UsernameSetupRequest;
import com.inninglog.domain.auth.service.ProfileSetupService;
import com.inninglog.domain.auth.service.UsernamePolicy;
import io.swagger.v3.oas.annotations.Operation;
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
@RequestMapping("/api/v1/onboarding")
@Validated
public class OnboardingController {

    private final ProfileSetupService profileSetupService;

    public OnboardingController(ProfileSetupService profileSetupService) {
        this.profileSetupService = profileSetupService;
    }

    @Operation(summary = "Get the current onboarding step")
    @GetMapping
    public OnboardingStatusResponse getStatus(JwtAuthenticationToken authentication) {
        return profileSetupService.getOnboardingStatus(authentication.getName());
    }

    @Operation(summary = "Check whether an onboarding username is available")
    @GetMapping("/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @RequestParam
            @NotBlank
            @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN)
            String username
    ) {
        return profileSetupService.checkUsernameAvailability(authentication.getName(), username);
    }

    @Operation(summary = "Set the onboarding username")
    @PutMapping("/username")
    public OnboardingStatusResponse setupUsername(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody UsernameSetupRequest request
    ) {
        return profileSetupService.setupUsername(authentication.getName(), request.username());
    }

    @Operation(summary = "Set the onboarding nickname")
    @PutMapping("/nickname")
    public OnboardingStatusResponse setupNickname(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody NicknameSetupRequest request
    ) {
        return profileSetupService.setupNickname(authentication.getName(), request.nickname());
    }

    @Operation(summary = "Select the initial favorite KBO team and complete onboarding")
    @PutMapping("/favorite-team")
    public OnboardingStatusResponse selectFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamSelectionRequest request
    ) {
        return profileSetupService.completeOnboarding(authentication.getName(), request.favoriteTeamId());
    }
}
