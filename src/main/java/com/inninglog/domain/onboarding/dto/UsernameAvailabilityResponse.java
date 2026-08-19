package com.inninglog.domain.onboarding.dto;

public record UsernameAvailabilityResponse(
        String username,
        boolean available
) {
}
