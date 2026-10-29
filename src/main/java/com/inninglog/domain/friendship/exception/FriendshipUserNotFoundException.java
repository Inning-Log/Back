package com.inninglog.domain.friendship.exception;

public class FriendshipUserNotFoundException extends RuntimeException {

    public FriendshipUserNotFoundException() {
        super("친구로 추가할 수 없는 사용자입니다.");
    }
}
