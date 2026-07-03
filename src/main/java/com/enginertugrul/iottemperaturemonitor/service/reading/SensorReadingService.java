package com.enginertugrul.iottemperaturemonitor.service.reading;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorDailyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorViewDTO;

import java.time.LocalDate;
import java.util.List;

public interface SensorReadingService {


    void saveTemperatureReading(String sensorToken, Double celsiusValue);

    List<SensorViewDTO> getRecentTenRecords(Long sensorId, Long ownerId);

    List<SensorDailyAverageDTO> getDailyAverageFromLastWeek(Long sensorId, Long ownerId);

    List<SensorHourlyAverageDTO> getHourlyAverageForDate(Long sensorId, Long ownerId, LocalDate date);

    LocalDate getTodayForSensor(Long sensorId, Long ownerId);


}
