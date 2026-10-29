package com.enginertugrul.iotsensormonitor.entity.sensor;

import lombok.Getter;

@Getter
public enum SensorType {

    TEMPERATURE(ReadingValueKind.NUMERIC),
    HUMIDITY(ReadingValueKind.NUMERIC),
    MOTION(ReadingValueKind.BOOLEAN);

    private final ReadingValueKind readingValueKind;

    SensorType(ReadingValueKind readingValueKind) {
        this.readingValueKind = readingValueKind;
    }

}