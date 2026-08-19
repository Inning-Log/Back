package com.inninglog.domain.oauth.controller;

import com.inninglog.domain.oauth.exception.InvalidGoogleTokenException;
import com.inninglog.domain.oauth.exception.OAuthProviderNotConfiguredException;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = GoogleOAuthController.class)
public class OAuthExceptionHandler {

    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ResponseEntity<OAuthErrorResponse> handleInvalidGoogleToken(InvalidGoogleTokenException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new OAuthErrorResponse("INVALID_GOOGLE_TOKEN", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<OAuthErrorResponse> handleAccountDeleted(AccountDeletedException exception) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new OAuthErrorResponse("ACCOUNT_DELETED", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<OAuthErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new OAuthErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(OAuthProviderNotConfiguredException.class)
    public ResponseEntity<OAuthErrorResponse> handleProviderConfiguration(
            OAuthProviderNotConfiguredException exception
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new OAuthErrorResponse("AUTH_PROVIDER_NOT_CONFIGURED", exception.getMessage(), Instant.now()));
    }

    public record OAuthErrorResponse(String code, String message, Instant timestamp) {
    }
}
