package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.time.Instant;

public record HourlyRollupRunResult(
        Status status,
        int sensorCount,
        int attemptedBuckets,
        int advancedBuckets,
        int refreshedBuckets,
        long sourceRowsSummarized,
        int failedSensors,
        boolean bounded,
        Instant oldestCoveredUntil
) {

    public enum Status {

        NO_WORK,
        SUCCEEDED,
        BOUNDED,
        PARTIAL_FAILURE
    }
}