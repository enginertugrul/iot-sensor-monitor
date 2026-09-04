package com.enginertugrul.iotsensormonitor.exception;

import java.util.NoSuchElementException;

public class AlertRuleNotFoundException extends NoSuchElementException {

    public AlertRuleNotFoundException() {
        super("Alert rule not found");
    }
}