package com.enginertugrul.iotsensormonitor.entity.sensor;

import lombok.Getter;

@Getter
public enum SensorType {

    TEMPERATURE("Temperature sensor",ReadingValueKind.NUMERIC),
    HUMIDITY("Humidity sensor",ReadingValueKind.NUMERIC),
    MOTION("Motion sensor",ReadingValueKind.BOOLEAN);

    private final String displayName;
    private final ReadingValueKind readingValueKind;

    SensorType(String displayName,ReadingValueKind readingValueKind) {
        this.displayName = displayName;
        this.readingValueKind = readingValueKind;
    }

}