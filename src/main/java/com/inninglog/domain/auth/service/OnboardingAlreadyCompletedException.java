package com.inninglog.domain.auth.service;

public class OnboardingAlreadyCompletedException extends RuntimeException {

    public OnboardingAlreadyCompletedException() {
        super("Onboarding has already been completed.");
    }
}
