package com.inninglog.domain.user.exception;

public class AccountDeletedException extends RuntimeException {

    public AccountDeletedException() {
        super("탈퇴한 계정입니다.");
    }
}
