package com.inninglog.domain.friendship.exception;

public class AlreadyFriendsException extends RuntimeException {

    public AlreadyFriendsException() {
        super("이미 친구인 사용자입니다.");
    }
}
