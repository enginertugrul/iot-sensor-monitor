package com.enginertugrul.iotsensormonitor.dto.reading;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

public record SensorReadingSnapshotDTO(
        SensorType sensorType,
        String installationLocation,
        Double numericValue,
        Boolean booleanValue,
        String unitSymbol,
        String timestamp,
        String timeZoneId,
        String offset
) {
}