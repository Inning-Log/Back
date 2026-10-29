package com.inninglog.domain.user.exception;

public class ProfileSetupRequiredException extends RuntimeException {

    public ProfileSetupRequiredException() {
        super("아이디와 닉네임을 설정한 뒤 응원팀을 선택해 주세요.");
    }
}
