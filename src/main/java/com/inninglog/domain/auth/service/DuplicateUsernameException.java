package com.inninglog.domain.auth.service;

public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException() {
        super("Username is already in use.");
    }
}
