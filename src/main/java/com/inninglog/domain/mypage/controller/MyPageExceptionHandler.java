package com.inninglog.domain.mypage.controller;

import com.inninglog.domain.auth.service.AuthUserNotFoundException;
import com.inninglog.domain.auth.service.DuplicateUsernameException;
import com.inninglog.domain.team.service.TeamNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = MyPageController.class)
public class MyPageExceptionHandler {

    @ExceptionHandler(AuthUserNotFoundException.class)
    public ResponseEntity<MyPageErrorResponse> handleUserNotFound(AuthUserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MyPageErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateUsernameException.class)
    public ResponseEntity<MyPageErrorResponse> handleDuplicateUsername(DuplicateUsernameException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MyPageErrorResponse("USERNAME_ALREADY_EXISTS", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(TeamNotFoundException.class)
    public ResponseEntity<MyPageErrorResponse> handleTeamNotFound(TeamNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MyPageErrorResponse("TEAM_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<MyPageErrorResponse> handleConstraintViolation(ConstraintViolationException exception) {
        return ResponseEntity.badRequest()
                .body(new MyPageErrorResponse("INVALID_REQUEST", exception.getMessage(), Instant.now()));
    }

    public record MyPageErrorResponse(String code, String message, Instant timestamp) {
    }
}
