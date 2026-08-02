package com.enginertugrul.iotsensormonitor.exception;

public class DuplicateSensorNameException extends IllegalArgumentException {

    public DuplicateSensorNameException() {
        super("You already have a sensor with this name");
    }
}