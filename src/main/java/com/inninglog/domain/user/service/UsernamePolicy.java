package com.inninglog.domain.user.service;

import java.util.Locale;

public final class UsernamePolicy {

    public static final int MAX_LENGTH = 30;
    public static final String PATTERN = "^\\s*[a-zA-Z0-9._]+\\s*$";

    private UsernamePolicy() {
    }

    public static String normalize(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
