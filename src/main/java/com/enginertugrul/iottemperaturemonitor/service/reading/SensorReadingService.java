package com.enginertugrul.iottemperaturemonitor.service.reading;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorDailyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorViewDTO;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.time.LocalDate;
import java.util.List;

public interface SensorReadingService {


    List<SensorViewDTO> getRecentTenRecords(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    List<SensorDailyAverageDTO> getDailyAverageForNumericValueFromLastWeek(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit);

    List<SensorHourlyAverageDTO> getHourlyAverageForDate(Long sensorId, Long ownerId, LocalDate date, TemperatureUnit temperatureUnit);

    LocalDate getTodayForSensor(Long sensorId, Long ownerId);


}
