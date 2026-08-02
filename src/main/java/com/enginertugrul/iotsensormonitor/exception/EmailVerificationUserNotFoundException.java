package com.enginertugrul.iotsensormonitor.exception;

public class EmailVerificationUserNotFoundException extends RuntimeException {

    public EmailVerificationUserNotFoundException() {
        super("User not found for initial email verification");
    }
}