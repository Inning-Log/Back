package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.service.AuthUserNotFoundException;
import com.inninglog.domain.auth.service.DuplicateUsernameException;
import com.inninglog.domain.team.service.TeamNotFoundException;
import com.inninglog.domain.user.entity.FavoriteTeamAlreadySelectedException;
import com.inninglog.domain.user.entity.ProfileSetupRequiredException;
import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(BadJwtException.class)
    public ResponseEntity<AuthErrorResponse> handleBadJwtException(BadJwtException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse("INVALID_GOOGLE_TOKEN", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<AuthErrorResponse> handleIllegalStateException(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new AuthErrorResponse("AUTH_PROVIDER_NOT_CONFIGURED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<AuthErrorResponse> handleDuplicateUsername(DuplicateUsernameException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthErrorResponse(
                        "USERNAME_ALREADY_EXISTS",
                        "Username is already in use.",
                        Instant.now()));
    }

    @ExceptionHandler(AuthUserNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleUserNotFound(AuthUserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleTeamNotFound(TeamNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse("TEAM_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ProfileSetupRequiredException.class)
    public ResponseEntity<AuthErrorResponse> handleProfileSetupRequired(ProfileSetupRequiredException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthErrorResponse("PROFILE_SETUP_REQUIRED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(FavoriteTeamAlreadySelectedException.class)
    public ResponseEntity<AuthErrorResponse> handleFavoriteTeamAlreadySelected(
            FavoriteTeamAlreadySelectedException exception
    ) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new AuthErrorResponse("FAVORITE_TEAM_ALREADY_SELECTED", exception.getMessage(), Instant.now()));
    }

    public record AuthErrorResponse(String code, String message, Instant timestamp) {
    }
}
