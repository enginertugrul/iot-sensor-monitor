package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;

import java.time.Instant;

public interface RollupSensorProjection {

    Long getId();
    SensorType getType();
    String getTimezone();
    Instant getFirstReadingAt();
}
