package com.inninglog.domain.mypage.controller;

import com.inninglog.domain.team.exception.TeamNotFoundException;
import com.inninglog.domain.user.exception.AccountDeletedException;
import com.inninglog.domain.user.exception.DuplicateUsernameException;
import com.inninglog.domain.user.exception.UserNotFoundException;
import com.inninglog.domain.mypage.image.ProfileImageException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {MyPageController.class, ProfileImageController.class})
public class MyPageExceptionHandler {

    @ExceptionHandler(ProfileImageException.class)
    public ResponseEntity<MyPageErrorResponse> handleProfileImage(ProfileImageException exception) {
        return ResponseEntity.status(exception.status())
                .body(new MyPageErrorResponse(exception.code(), exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<MyPageErrorResponse> handleInvalidImageRequest(Exception exception) {
        return ResponseEntity.badRequest()
                .body(new MyPageErrorResponse("INVALID_REQUEST", "요청 형식과 필수 값을 확인해 주세요.", Instant.now()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<MyPageErrorResponse> handleUserNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new MyPageErrorResponse("USER_NOT_FOUND", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(AccountDeletedException.class)
    public ResponseEntity<MyPageErrorResponse> handleAccountDeleted(AccountDeletedException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new MyPageErrorResponse("ACCOUNT_DELETED", exception.getMessage(), Instant.now()));
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
