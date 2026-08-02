package com.enginertugrul.iotsensormonitor.dto.reading;

import java.time.LocalDate;



public record SensorDailyStatisticDTO(
        LocalDate date,
        Double value
) {
}