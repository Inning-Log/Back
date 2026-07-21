package com.inninglog.domain.user.entity;

public class ProfileSetupRequiredException extends RuntimeException {

    public ProfileSetupRequiredException() {
        super("Username and nickname must be set before selecting a favorite team.");
    }
}
