package com.inninglog.domain.mypage.dto;

public record UsernameAvailabilityResponse(
        String username,
        boolean available
) {
}
