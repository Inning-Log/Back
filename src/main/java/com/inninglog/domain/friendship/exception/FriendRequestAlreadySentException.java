package com.inninglog.domain.friendship.exception;

public class FriendRequestAlreadySentException extends RuntimeException {

    public FriendRequestAlreadySentException() {
        super("A friend request has already been sent to this user.");
    }
}
