package com.inninglog.domain.user.exception;

public class DuplicateUsernameException extends RuntimeException {

    public DuplicateUsernameException() {
        super("Username is already in use.");
    }
}
