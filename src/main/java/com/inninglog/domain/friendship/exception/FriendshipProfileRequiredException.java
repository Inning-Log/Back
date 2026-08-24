package com.inninglog.domain.friendship.exception;

public class FriendshipProfileRequiredException extends RuntimeException {

    public FriendshipProfileRequiredException() {
        super("Complete onboarding before using friendship features.");
    }
}
