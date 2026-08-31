package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;

import java.util.List;

public interface SensorReadingService {


    List<SensorReadingViewDTO> getRecentReadings(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

}
