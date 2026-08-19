package com.inninglog.domain.auth.service;

public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid, expired, rotated, or revoked.");
    }
}
