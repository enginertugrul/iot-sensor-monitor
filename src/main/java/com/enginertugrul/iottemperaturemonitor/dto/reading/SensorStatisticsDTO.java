package com.enginertugrul.iottemperaturemonitor.dto.reading;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;

import java.time.LocalDate;
import java.util.List;

public record SensorStatisticsDTO(
        SensorType sensorType,
        String measurementUnitSymbol,
        LocalDate today,
        List<SensorDailyStatisticDTO> weeklyData,
        List<SensorHourlyStatisticDTO> hourlyData
) {
    public SensorStatisticsDTO {
        weeklyData = List.copyOf(weeklyData);
        hourlyData = List.copyOf(hourlyData);
    }
}