package com.inninglog.domain.user.controller;

import com.inninglog.domain.user.exception.UserNotFoundException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = AccountController.class)
public class UserExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<UserErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new UserErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    public record UserErrorResponse(String code, String message, Instant timestamp) {
    }
}
