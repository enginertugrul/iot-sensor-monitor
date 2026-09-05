package com.enginertugrul.iotsensormonitor.dto.reading;

import java.util.List;
import java.util.Objects;

public record RecentSensorReadingsDTO(Long sensorId, List<SensorReadingSnapshotDTO> readings) {

    public RecentSensorReadingsDTO {
        Objects.requireNonNull(sensorId,"sensorId must not be null");
        readings = List.copyOf(Objects.requireNonNull(readings,"readings must not be null"));

        if (readings.size() > 10) {
            throw new IllegalArgumentException("readings must contain at most 10 entries");
        }
    }
}