package com.enginertugrul.iotsensormonitor.exception;


public class InvalidSensorReadingException extends RuntimeException {

    public InvalidSensorReadingException(String message) {
        super(message);
    }

    public InvalidSensorReadingException(String message,Throwable cause) {
        super(message, cause);
    }
}