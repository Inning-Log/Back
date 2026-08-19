package com.inninglog.domain.auth.dto;

import com.inninglog.domain.user.dto.UserResponse;
import java.util.List;

public record CurrentUserResponse(
        String subject,
        List<String> authorities,
        UserResponse user
) {
}
