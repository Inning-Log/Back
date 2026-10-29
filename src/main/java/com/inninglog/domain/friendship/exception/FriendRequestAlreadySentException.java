package com.inninglog.domain.friendship.exception;

public class FriendRequestAlreadySentException extends RuntimeException {

    public FriendRequestAlreadySentException() {
        super("이미 친구 신청을 보낸 사용자입니다.");
    }
}
