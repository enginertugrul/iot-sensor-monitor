package com.enginertugrul.iotsensormonitor.entity.sensor;

import lombok.Getter;

@Getter
public enum SensorType {

    TEMPERATURE("Temperature sensor"),
    HUMIDITY("Humidity sensor"),
    MOTION("Motion sensor");

    private final String displayName;

    SensorType(String displayName) {
        this.displayName = displayName;
    }
}