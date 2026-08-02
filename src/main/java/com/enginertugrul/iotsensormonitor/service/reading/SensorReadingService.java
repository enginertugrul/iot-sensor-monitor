package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.dto.reading.SensorHourlyStatisticDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorStatisticsDTO;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;

import java.time.LocalDate;
import java.util.List;

public interface SensorReadingService {


    List<SensorReadingViewDTO> getRecentReadings(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    SensorStatisticsDTO getStatistics(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    List<SensorHourlyStatisticDTO> getHourlyStatisticsForDate(Long sensorId, Long ownerId, LocalDate date, TemperatureUnit temperatureUnit);


}
