package com.enginertugrul.iotsensormonitor.exception;

public class InvalidAlertRuleException extends IllegalArgumentException {

  public InvalidAlertRuleException(String message) {
    super(message);
  }

  public InvalidAlertRuleException(String message,Throwable cause) {
    super(message,cause);
  }
}