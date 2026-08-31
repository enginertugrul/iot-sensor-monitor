package com.enginertugrul.iotsensormonitor.exception;

public class SensorTimezoneLockedException extends IllegalStateException {

    public SensorTimezoneLockedException() {
        super("Sensor timezone cannot be changed after readings have been recorded");
    }
}