package com.inninglog.domain.auth.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("로그인 정보가 만료되었거나 유효하지 않습니다. 다시 로그인해 주세요.");
    }
}
