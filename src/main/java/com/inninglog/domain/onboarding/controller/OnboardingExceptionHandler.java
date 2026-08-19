package com.inninglog.domain.onboarding.controller;

import com.inninglog.domain.onboarding.exception.OnboardingAlreadyCompletedException;
import com.inninglog.domain.team.exception.TeamNotFoundException;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.DuplicateUsernameException;
import com.inninglog.domain.user.exception.FavoriteTeamAlreadySelectedException;
import com.inninglog.domain.user.exception.ProfileSetupRequiredException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {OnboardingController.class, LegacyOnboardingController.class})
public class OnboardingExceptionHandler {

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<OnboardingErrorResponse> handleDuplicateUsername(DuplicateUsernameException exception) {
        return error(HttpStatus.CONFLICT, "USERNAME_ALREADY_EXISTS", "Username is already in use.");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<OnboardingErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<OnboardingErrorResponse> handleAccountDeleted(AccountDeletedException exception) {
        return error(HttpStatus.CONFLICT, "ACCOUNT_DELETED", exception.getMessage());
    }

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<OnboardingErrorResponse> handleTeamNotFound(TeamNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(ProfileSetupRequiredException.class)
    public ResponseEntity<OnboardingErrorResponse> handleProfileSetupRequired(ProfileSetupRequiredException exception) {
        return error(HttpStatus.CONFLICT, "PROFILE_SETUP_REQUIRED", exception.getMessage());
    }

    @ExceptionHandler(FavoriteTeamAlreadySelectedException.class)
    public ResponseEntity<OnboardingErrorResponse> handleFavoriteTeamSelected(
            FavoriteTeamAlreadySelectedException exception
    ) {
        return error(HttpStatus.CONFLICT, "FAVORITE_TEAM_ALREADY_SELECTED", exception.getMessage());
    }

    @ExceptionHandler(OnboardingAlreadyCompletedException.class)
    public ResponseEntity<OnboardingErrorResponse> handleOnboardingCompleted(
            OnboardingAlreadyCompletedException exception
    ) {
        return error(HttpStatus.CONFLICT, "ONBOARDING_ALREADY_COMPLETED", exception.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<OnboardingErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage());
    }

    private ResponseEntity<OnboardingErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new OnboardingErrorResponse(code, message, Instant.now()));
    }

    public record OnboardingErrorResponse(String code, String message, Instant timestamp) {
    }
}
