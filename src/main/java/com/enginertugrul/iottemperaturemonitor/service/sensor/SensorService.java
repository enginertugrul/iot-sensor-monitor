package com.enginertugrul.iottemperaturemonitor.service.sensor;

import com.enginertugrul.iottemperaturemonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorForm;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;

import java.util.List;

public interface SensorService {

    CreatedSensorDTO createSensor(Long ownerId, SensorForm sensorForm);

    List<SensorListItemDTO> getSensorsForUser(Long ownerId);

    Sensor getSensorForUser(Long sensorId, Long ownerId);

    SensorUpdateForm getSensorUpdateForm(Long sensorId, Long ownerId);

    void updateSensor(Long sensorId, Long ownerId, SensorUpdateForm sensorUpdateForm);

    String getDefaultTimezoneForUser(Long ownerId);
}