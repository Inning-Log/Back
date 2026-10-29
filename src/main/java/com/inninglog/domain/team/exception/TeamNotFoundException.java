package com.inninglog.domain.team.exception;

public class TeamNotFoundException extends RuntimeException {

    public TeamNotFoundException() {
        super("구단 정보를 찾을 수 없습니다.");
    }
}
