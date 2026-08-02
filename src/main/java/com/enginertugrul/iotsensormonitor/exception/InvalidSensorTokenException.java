package com.enginertugrul.iotsensormonitor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidSensorTokenException extends RuntimeException {

    public InvalidSensorTokenException() {
        super("Invalid sensor token");
    }
}