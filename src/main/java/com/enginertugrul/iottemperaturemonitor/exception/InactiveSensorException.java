package com.enginertugrul.iottemperaturemonitor.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class InactiveSensorException extends RuntimeException {

  public InactiveSensorException() {
    super("Sensor is not active");
  }
}