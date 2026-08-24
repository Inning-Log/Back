package com.inninglog.domain.friendship.exception;

public class FriendshipUserNotFoundException extends RuntimeException {

    public FriendshipUserNotFoundException() {
        super("The requested user is not available for friendship.");
    }
}
