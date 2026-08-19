package com.inninglog.domain.onboarding.exception;

public class OnboardingAlreadyCompletedException extends RuntimeException {

    public OnboardingAlreadyCompletedException() {
        super("Onboarding has already been completed.");
    }
}
