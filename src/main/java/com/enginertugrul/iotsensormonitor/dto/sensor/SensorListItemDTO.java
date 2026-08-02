package com.enginertugrul.iotsensormonitor.dto.sensor;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;




public record SensorListItemDTO(
        Long id,
        String name,
        SensorType type,
        String city,
        String district,
        String homeLocation,
        String timezone,
        boolean active
) {
}