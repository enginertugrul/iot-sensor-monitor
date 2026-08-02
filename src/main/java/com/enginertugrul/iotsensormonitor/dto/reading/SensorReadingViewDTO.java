package com.enginertugrul.iotsensormonitor.dto.reading;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

import java.time.ZonedDateTime;




public record SensorReadingViewDTO(
        SensorType sensorType,
        String location,
        Double numericValue,
        Boolean booleanValue,
        String unitSymbol,
        ZonedDateTime timestamp
) {
}