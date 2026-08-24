package com.inninglog.domain.friendship.exception;

public class FriendshipNotFoundException extends RuntimeException {

    public FriendshipNotFoundException() {
        super("Friendship was not found.");
    }
}
