package com.inninglog.domain.game.exception;

import org.springframework.http.HttpStatus;

public class GameException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public GameException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }

    public static GameException notFound(String code) {
        return new GameException(HttpStatus.NOT_FOUND, code, "요청한 정보를 찾을 수 없습니다.");
    }

    public static GameException conflict(String code, String message) {
        return new GameException(HttpStatus.CONFLICT, code, message);
    }
}
