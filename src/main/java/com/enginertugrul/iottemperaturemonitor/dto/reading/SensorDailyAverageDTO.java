package com.enginertugrul.iottemperaturemonitor.dto.reading;


import java.time.LocalDate;

public record SensorDailyAverageDTO(LocalDate date, Double averageTemperature) {

}
