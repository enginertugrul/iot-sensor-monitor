package com.enginertugrul.iotsensormonitor.exception;

public class EmailAlreadyRegisteredException extends IllegalArgumentException {

    public EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }

    public EmailAlreadyRegisteredException(Throwable cause) {
        super("Email is already registered",cause);
    }
}