package com.inninglog.domain.auth.service;

public class AccountDeletedException extends RuntimeException {

    public AccountDeletedException() {
        super("This account has been deleted.");
    }
}
