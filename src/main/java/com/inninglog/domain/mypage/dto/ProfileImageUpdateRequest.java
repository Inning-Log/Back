package com.inninglog.domain.mypage.dto;

import jakarta.validation.constraints.Size;

public record ProfileImageUpdateRequest(
        @Size(max = 500) String profileImageUrl
) {
}
