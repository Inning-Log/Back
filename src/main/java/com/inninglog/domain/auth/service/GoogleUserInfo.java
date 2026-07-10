package com.inninglog.domain.auth.service;

public record GoogleUserInfo(
        String subject,
        String email,
        String name,
        String picture
) {
}
