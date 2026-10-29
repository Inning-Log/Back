package com.inninglog.domain.friendship.exception;

public class FriendshipNotFoundException extends RuntimeException {

    public FriendshipNotFoundException() {
        super("친구 관계 또는 친구 신청을 찾을 수 없습니다.");
    }
}
