package com.enginertugrul.iottemperaturemonitor.dto.reading;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;

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