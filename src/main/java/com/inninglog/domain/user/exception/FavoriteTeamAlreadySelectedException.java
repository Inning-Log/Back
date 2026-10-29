package com.inninglog.domain.user.exception;

public class FavoriteTeamAlreadySelectedException extends RuntimeException {

    public FavoriteTeamAlreadySelectedException() {
        super("이미 응원팀을 선택했습니다.");
    }
}
