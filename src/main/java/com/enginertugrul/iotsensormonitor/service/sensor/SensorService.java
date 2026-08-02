package com.enginertugrul.iotsensormonitor.service.sensor;

import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;

import java.util.List;

public interface SensorService {

    CreatedSensorDTO createSensor(Long ownerId, SensorForm sensorForm);

    List<SensorListItemDTO> getSensorsForUser(Long ownerId);

    Sensor getSensorForUser(Long sensorId, Long ownerId);

    SensorUpdateForm getSensorUpdateForm(Long sensorId, Long ownerId);

    void updateSensor(Long sensorId, Long ownerId, SensorUpdateForm sensorUpdateForm);

    void deleteSensor(Long sensorId,Long ownerId);

    String getDefaultTimezoneForUser(Long ownerId);
}