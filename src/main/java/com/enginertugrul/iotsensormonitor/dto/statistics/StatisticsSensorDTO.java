package com.enginertugrul.iotsensormonitor.dto.statistics;

import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

public record StatisticsSensorDTO(
        Long id,
        String name,
        SensorType type,
        String timeZoneId,
        MeasurementUnit canonicalUnit,
        String displayUnit,
        String displayUnitSymbol
) {
}