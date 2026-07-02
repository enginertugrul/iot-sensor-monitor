package com.enginertugrul.iottemperaturemonitor.dto.reading;

import java.time.ZonedDateTime;

public record SensorViewDTO(String locationOfSensor, Double temperatureValue, ZonedDateTime timestamp) {

}
