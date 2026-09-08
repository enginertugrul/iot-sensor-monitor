package com.enginertugrul.iotsensormonitor.exception;

public class SensorReadingStreamUnavailableException extends IllegalStateException {

    public SensorReadingStreamUnavailableException() {
        super("Recent reading streaming is temporarily unavailable");
    }
}