package com.enginertugrul.iotsensormonitor.service.user.password;

public record PasswordChangedEvent(Long userId) {

    public PasswordChangedEvent {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }

}