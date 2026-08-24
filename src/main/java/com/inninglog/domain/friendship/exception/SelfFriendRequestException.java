package com.inninglog.domain.friendship.exception;

public class SelfFriendRequestException extends RuntimeException {

    public SelfFriendRequestException() {
        super("A user cannot send a friend request to themselves.");
    }
}
