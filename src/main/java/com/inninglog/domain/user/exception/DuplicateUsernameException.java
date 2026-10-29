package com.inninglog.domain.user.exception;

public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException() {
        super("이미 사용 중인 아이디입니다.");
    }
}
