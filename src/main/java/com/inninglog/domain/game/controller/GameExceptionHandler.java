package com.inninglog.domain.game.controller;

import com.inninglog.domain.game.exception.GameException;
import com.inninglog.domain.home.controller.HomeController;
import com.inninglog.domain.timeline.controller.InningRecordController;
import com.inninglog.domain.timeline.controller.TimelineController;
import jakarta.validation.ConstraintViolationException;
import java.time.DateTimeException;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {GameController.class, UserGameLogController.class, HomeController.class,
        TimelineController.class, InningRecordController.class})
public class GameExceptionHandler {
    @ExceptionHandler(GameException.class)
    public ResponseEntity<GameErrorResponse> domain(GameException error) {
        return ResponseEntity.status(error.status()).body(new GameErrorResponse(error.code(), error.getMessage(), Instant.now()));
    }

    @ExceptionHandler({IllegalArgumentException.class, DateTimeException.class, ConstraintViolationException.class,
            HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MissingServletRequestParameterException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<GameErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest().body(new GameErrorResponse("INVALID_REQUEST", "요청 형식과 필수 값을 확인해 주세요.", Instant.now()));
    }

    public record GameErrorResponse(String code, String message, Instant timestamp) {}
}
