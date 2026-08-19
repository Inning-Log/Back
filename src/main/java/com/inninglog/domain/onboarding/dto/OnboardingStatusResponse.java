package com.inninglog.domain.onboarding.dto;

import com.inninglog.domain.user.dto.UserResponse;
import com.inninglog.domain.user.entity.User;

public record OnboardingStatusResponse(
        OnboardingStep nextStep,
        boolean completed,
        UserResponse user
) {

    public static OnboardingStatusResponse from(User user) {
        return new OnboardingStatusResponse(
                findNextStep(user),
                user.isOnboardingCompleted(),
                UserResponse.from(user));
    }

    private static OnboardingStep findNextStep(User user) {
        if (user.isOnboardingCompleted()) {
            return OnboardingStep.COMPLETED;
        }
        if (user.getUsername() == null || user.getUsername().isBlank()) {
            return OnboardingStep.USERNAME;
        }
        if (user.getNickname() == null || user.getNickname().isBlank()) {
            return OnboardingStep.NICKNAME;
        }
        return OnboardingStep.FAVORITE_TEAM;
    }
}
