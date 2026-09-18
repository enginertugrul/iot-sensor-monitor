package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly;

import java.time.Instant;

public record HourlyRollupBucketResult(
        Status status,
        long sensorId,
        Instant bucketStart,
        Instant bucketEnd,
        Instant coverageStartedAt,
        Instant coveredUntil,
        long sourceSampleCount
) {

    public enum Status {

        ADVANCED,
        UP_TO_DATE,
    }

}