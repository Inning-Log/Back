package com.inninglog.domain.notification.controller;

import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.notification.exception.InvalidNotificationSettingsException;
import com.inninglog.domain.notification.exception.NotificationNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {
        NotificationController.class,
        NotificationSettingsController.class
})
public class NotificationExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<NotificationErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler({ConstraintViolationException.class, MethodArgumentNotValidException.class,
            HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<NotificationErrorResponse> handleConstraintViolation(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new NotificationErrorResponse("INVALID_REQUEST", "요청 형식과 필수 값을 확인해 주세요.", Instant.now()));
    }

    @ExceptionHandler(NotificationNotFoundException.class)
    public ResponseEntity<NotificationErrorResponse> handleNotificationNotFound(
            NotificationNotFoundException exception
    ) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new NotificationErrorResponse(
                        "NOTIFICATION_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(InvalidNotificationSettingsException.class)
    public ResponseEntity<NotificationErrorResponse> handleInvalidSettings(
            InvalidNotificationSettingsException exception
    ) {
        return ResponseEntity.badRequest()
                .body(new NotificationErrorResponse(
                        "INVALID_NOTIFICATION_SETTINGS", exception.getMessage(), Instant.now()));
    }

    public record NotificationErrorResponse(String code, String message, Instant timestamp) {
    }
}
