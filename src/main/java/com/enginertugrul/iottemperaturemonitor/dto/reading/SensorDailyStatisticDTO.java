package com.enginertugrul.iottemperaturemonitor.dto.reading;

import java.time.LocalDate;

public record SensorDailyStatisticDTO(
        LocalDate date,
        Double value
) {
}