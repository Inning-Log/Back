package com.inninglog.domain.team.service;

public class TeamNotFoundException extends RuntimeException {

    public TeamNotFoundException() {
        super("KBO team was not found.");
    }
}
