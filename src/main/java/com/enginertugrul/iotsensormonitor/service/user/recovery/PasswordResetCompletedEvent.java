package com.enginertugrul.iotsensormonitor.service.user.recovery;

public record PasswordResetCompletedEvent(Long userId) {

    public PasswordResetCompletedEvent {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }
    }
}