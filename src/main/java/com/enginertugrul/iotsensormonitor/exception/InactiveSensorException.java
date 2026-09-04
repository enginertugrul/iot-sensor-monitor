package com.enginertugrul.iotsensormonitor.exception;


public class InactiveSensorException extends RuntimeException {

  public InactiveSensorException() {
    super("Sensor is not active");
  }
}