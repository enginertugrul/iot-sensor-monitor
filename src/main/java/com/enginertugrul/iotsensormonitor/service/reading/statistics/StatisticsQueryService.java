package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.SensorStatisticsSeriesDTO;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;

import java.time.Instant;

public interface StatisticsQueryService {

    SensorStatisticsSeriesDTO getSeries(
            Long sensorId,
            Long ownerId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsResolution requestedResolution,
            TemperatureUnit temperatureUnit
    );
}