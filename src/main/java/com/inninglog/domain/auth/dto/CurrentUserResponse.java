package com.inninglog.domain.auth.dto;

import java.util.List;

public record CurrentUserResponse(
        String subject,
        List<String> authorities,
        UserResponse user
) {
}
