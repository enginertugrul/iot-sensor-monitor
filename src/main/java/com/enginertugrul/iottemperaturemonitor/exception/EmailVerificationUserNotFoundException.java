package com.enginertugrul.iottemperaturemonitor.exception;

public class EmailVerificationUserNotFoundException extends RuntimeException {

    public EmailVerificationUserNotFoundException() {
        super("User not found for initial email verification");
    }
}