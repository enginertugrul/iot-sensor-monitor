package com.enginertugrul.iotsensormonitor.exception;


import java.util.NoSuchElementException;

public class SensorNotFoundException extends NoSuchElementException {

    public SensorNotFoundException() {
        super("Sensor not found");
    }
}