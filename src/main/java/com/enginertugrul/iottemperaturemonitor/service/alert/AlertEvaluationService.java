package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;

public interface AlertEvaluationService {

    void evaluateReading(SensorReading reading);
}