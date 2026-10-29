package com.inninglog.domain.user.exception;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("사용자 정보를 찾을 수 없습니다.");
    }
}
