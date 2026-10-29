package com.inninglog.domain.onboarding.exception;

public class OnboardingAlreadyCompletedException extends RuntimeException {

    public OnboardingAlreadyCompletedException() {
        super("이미 가입 설정을 완료했습니다.");
    }
}
