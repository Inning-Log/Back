package com.inninglog.domain.auth.dto;

public record UsernameAvailabilityResponse(
        String username,
        boolean available
) {
}
