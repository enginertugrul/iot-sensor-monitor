package com.enginertugrul.iotsensormonitor.exception;


public class InvalidSensorTokenException extends RuntimeException {

    public InvalidSensorTokenException() {
        super("Invalid sensor token");
    }
}