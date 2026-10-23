package com.inninglog.domain.mypage.image;

import org.springframework.http.HttpStatus;

public class ProfileImageException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ProfileImageException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public HttpStatus status() { return status; }
    public String code() { return code; }
}
