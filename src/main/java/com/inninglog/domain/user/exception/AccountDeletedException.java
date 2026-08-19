package com.inninglog.domain.user.exception;

public class AccountDeletedException extends RuntimeException {

    public AccountDeletedException() {
        super("This account has been deleted.");
    }
}
