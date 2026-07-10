package com.inninglog.domain.auth.controller;

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

    public record AuthErrorResponse(String code, String message, Instant timestamp) {
    }
}
