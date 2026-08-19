package com.inninglog.domain.user.exception;

public class FavoriteTeamAlreadySelectedException extends RuntimeException {

    public FavoriteTeamAlreadySelectedException() {
        super("The initial favorite team has already been selected.");
    }
}
