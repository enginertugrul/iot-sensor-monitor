package com.enginertugrul.iottemperaturemonitor.dto.reading;



public record SensorHourlyStatisticDTO(
        Short hour,
        Double value
) {
}