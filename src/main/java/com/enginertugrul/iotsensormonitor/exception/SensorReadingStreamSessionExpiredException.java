package com.enginertugrul.iotsensormonitor.exception;

public class SensorReadingStreamSessionExpiredException extends IllegalStateException {

    public SensorReadingStreamSessionExpiredException() {
        super("The authenticated session has expired");
    }
}