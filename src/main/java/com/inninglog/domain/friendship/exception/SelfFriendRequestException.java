package com.inninglog.domain.friendship.exception;

public class SelfFriendRequestException extends RuntimeException {

    public SelfFriendRequestException() {
        super("자신에게는 친구 신청을 보낼 수 없습니다.");
    }
}
