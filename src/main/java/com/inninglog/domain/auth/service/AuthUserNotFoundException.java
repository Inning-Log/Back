package com.inninglog.domain.auth.service;

public class AuthUserNotFoundException extends RuntimeException {

    public AuthUserNotFoundException() {
        super("Authenticated user was not found.");
    }
}
