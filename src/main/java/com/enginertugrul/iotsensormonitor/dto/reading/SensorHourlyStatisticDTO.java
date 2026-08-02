package com.enginertugrul.iotsensormonitor.dto.reading;



public record SensorHourlyStatisticDTO(
        Short hour,
        Double value
) {
}