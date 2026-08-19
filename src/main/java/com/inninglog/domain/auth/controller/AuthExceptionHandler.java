package com.inninglog.domain.auth.controller;

import com.inninglog.domain.auth.exception.InvalidRefreshTokenException;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AuthSessionController.class)
public class AuthExceptionHandler {

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<AuthErrorResponse> handleInvalidRefreshToken(InvalidRefreshTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new AuthErrorResponse("INVALID_REFRESH_TOKEN", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<AuthErrorResponse> handleAccountDeleted(AccountDeletedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new AuthErrorResponse("ACCOUNT_DELETED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<AuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new AuthErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    public record AuthErrorResponse(String code, String message, Instant timestamp) {
    }
}
