package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;

import java.time.Instant;

public interface AlertEvaluationService {

    void evaluateTemperatureReading(Sensor sensor, Double celsiusValue, Instant recordedAt);
}