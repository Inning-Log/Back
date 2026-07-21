package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.dto.CurrentUserResponse;
import com.inninglog.domain.auth.dto.GoogleLoginRequest;
import com.inninglog.domain.auth.dto.LoginResponse;
import com.inninglog.domain.auth.dto.ProfileSetupRequest;
import com.inninglog.domain.auth.dto.UserResponse;
import com.inninglog.domain.auth.dto.UsernameAvailabilityResponse;
import com.inninglog.domain.auth.service.OAuthLoginService;
import com.inninglog.domain.auth.service.ProfileSetupService;
import com.inninglog.domain.user.repository.UserRepository;
import com.inninglog.global.security.JwtTokenProvider;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    @Operation(summary = "Issue a development JWT")
    @PostMapping("/dev-token")
    public ResponseEntity<JwtTokenProvider.IssuedToken> issueDevToken(
            @Valid @RequestBody DevTokenRequest request
    ) {
        if (!devTokenEnabled) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(jwtTokenProvider.issue(request.subject(), request.normalizedRoles()));
    }

    @Operation(summary = "Login with Google ID token")
    @PostMapping("/google")
    public LoginResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return oAuthLoginService.loginWithGoogle(request.credential());
    }

    @Operation(summary = "Set unique username and nickname")
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

    @Operation(summary = "Check whether a username is available")
    @GetMapping("/profile/username-availability")
    public UsernameAvailabilityResponse checkUsernameAvailability(
            JwtAuthenticationToken authentication,
            @RequestParam @NotBlank @Size(max = 80) String username
    ) {
        return profileSetupService.checkUsernameAvailability(authentication.getName(), username);
    }

    @Operation(summary = "Get current authenticated principal")
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

    public record DevTokenRequest(
            @NotBlank @Size(max = 80) String subject,
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
