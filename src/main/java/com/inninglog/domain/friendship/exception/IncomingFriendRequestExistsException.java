package com.inninglog.domain.friendship.exception;

public class IncomingFriendRequestExistsException extends RuntimeException {

    public IncomingFriendRequestExistsException() {
        super("This user has already sent you a friend request.");
    }
}
