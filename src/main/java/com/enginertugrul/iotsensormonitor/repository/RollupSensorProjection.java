package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

public interface RollupSensorProjection {

    Long getId();
    SensorType getType();
    String getTimezone();
}
