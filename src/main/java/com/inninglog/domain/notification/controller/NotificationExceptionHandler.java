package com.inninglog.domain.notification.controller;

import com.inninglog.domain.user.exception.UserNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = NotificationController.class)
public class NotificationExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<NotificationErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<NotificationErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(new NotificationErrorResponse("INVALID_REQUEST", exception.getMessage(), Instant.now()));
    }

    public record NotificationErrorResponse(String code, String message, Instant timestamp) {
    }
}
