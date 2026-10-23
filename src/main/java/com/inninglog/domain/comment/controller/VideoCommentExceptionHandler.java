package com.inninglog.domain.comment.controller;

import com.inninglog.domain.game.exception.GameException;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = VideoCommentController.class)
public class VideoCommentExceptionHandler {
    @ExceptionHandler(GameException.class)
    public ResponseEntity<ErrorResponse> domain(GameException error) {
        return ResponseEntity.status(error.status()).body(new ErrorResponse(error.code(), error.getMessage(), Instant.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            HandlerMethodValidationException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", "요청 형식과 필수 값을 확인해 주세요.", Instant.now()));
    }

    public record ErrorResponse(String code, String message, Instant timestamp) {}
}
