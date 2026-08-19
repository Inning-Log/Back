package com.inninglog.domain.team.exception;

public class TeamNotFoundException extends RuntimeException {

    public TeamNotFoundException() {
        super("KBO team was not found.");
    }
}
