package com.enginertugrul.iottemperaturemonitor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

import java.util.NoSuchElementException;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class SensorNotFoundException extends NoSuchElementException {

    public SensorNotFoundException() {
        super("Sensor not found");
    }
}