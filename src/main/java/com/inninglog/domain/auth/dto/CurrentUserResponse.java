package com.inninglog.domain.auth.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.inninglog.domain.user.dto.UserResponse;
import java.util.List;

public record CurrentUserResponse(
        String subject,
        List<String> authorities,
        @Schema(description = "DB 사용자가 없는 개발용 세션이면 null", nullable = true) UserResponse user
) {
}
