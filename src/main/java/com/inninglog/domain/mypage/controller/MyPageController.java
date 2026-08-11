package com.inninglog.domain.mypage.controller;

import com.inninglog.domain.auth.service.UsernamePolicy;
import com.inninglog.domain.mypage.dto.FavoriteTeamUpdateRequest;
import com.inninglog.domain.mypage.dto.MyPageResponse;
import com.inninglog.domain.mypage.dto.ProfileImageUpdateRequest;
import com.inninglog.domain.mypage.dto.ProfileUpdateRequest;
import com.inninglog.domain.mypage.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.mypage.service.MyPageService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mypage")
@Validated
public class MyPageController {

    private final MyPageService myPageService;

    public MyPageController(MyPageService myPageService) {
        this.myPageService = myPageService;
    }

    @Operation(summary = "Get my page profile")
    @GetMapping
    public MyPageResponse getMyPage(JwtAuthenticationToken authentication) {
        return myPageService.getMyPage(authentication.getName());
    }

    @Operation(summary = "Check whether a username is available for profile update")
    @GetMapping("/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @RequestParam
            @NotBlank
            @Size(max = UsernamePolicy.MAX_LENGTH)
            @Pattern(regexp = UsernamePolicy.PATTERN)
            String username
    ) {
        return myPageService.checkUsernameAvailability(authentication.getName(), username);
    }

    @Operation(summary = "Update username and nickname")
    @PatchMapping("/profile")
    public MyPageResponse updateProfile(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileUpdateRequest request
    ) {
        return myPageService.updateProfile(
                authentication.getName(),
                request.username(),
                request.nickname());
    }

    @Operation(summary = "Change favorite KBO team")
    @PutMapping("/favorite-team")
    public MyPageResponse updateFavoriteTeam(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody FavoriteTeamUpdateRequest request
    ) {
        return myPageService.updateFavoriteTeam(authentication.getName(), request.favoriteTeamId());
    }

    @Operation(summary = "Update or reset profile image URL")
    @PutMapping("/profile-image")
    public MyPageResponse updateProfileImage(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody ProfileImageUpdateRequest request
    ) {
        return myPageService.updateProfileImage(authentication.getName(), request.profileImageUrl());
    }
}
