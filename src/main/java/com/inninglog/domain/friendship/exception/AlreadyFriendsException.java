package com.inninglog.domain.friendship.exception;

public class AlreadyFriendsException extends RuntimeException {

    public AlreadyFriendsException() {
        super("The users are already friends.");
    }
}
