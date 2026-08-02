package com.enginertugrul.iottemperaturemonitor.service.reading;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyStatisticDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorStatisticsDTO;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.time.LocalDate;
import java.util.List;

public interface SensorReadingService {


    List<SensorReadingViewDTO> getRecentReadings(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    SensorStatisticsDTO getStatistics(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    List<SensorHourlyStatisticDTO> getHourlyStatisticsForDate(Long sensorId, Long ownerId, LocalDate date, TemperatureUnit temperatureUnit);


}
