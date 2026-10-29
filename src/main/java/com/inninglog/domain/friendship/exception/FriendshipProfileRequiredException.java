package com.inninglog.domain.friendship.exception;

public class FriendshipProfileRequiredException extends RuntimeException {

    public FriendshipProfileRequiredException() {
        super("가입 설정을 완료한 뒤 친구 기능을 이용해 주세요.");
    }
}
