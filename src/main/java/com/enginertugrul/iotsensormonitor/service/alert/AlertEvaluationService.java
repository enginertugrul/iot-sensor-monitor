package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;

public interface AlertEvaluationService {

    void evaluateReading(SensorReading reading);
}