package com.inninglog.domain.oauth.google;

public record GoogleUserInfo(
        String subject,
        String email,
        String name,
        String picture
) {
}
