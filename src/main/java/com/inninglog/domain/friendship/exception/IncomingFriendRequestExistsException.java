package com.inninglog.domain.friendship.exception;

public class IncomingFriendRequestExistsException extends RuntimeException {

    public IncomingFriendRequestExistsException() {
        super("이미 이 사용자에게 받은 친구 신청이 있습니다.");
    }
}
