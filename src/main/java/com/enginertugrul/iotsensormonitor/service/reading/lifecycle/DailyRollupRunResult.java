package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.time.Duration;
import java.time.Instant;

public record DailyRollupRunResult(
        Status status,
        int sensorCount,
        int attemptedBuckets,
        int advancedBuckets,
        int refreshedBuckets,
        long sourceRowsSummarized,
        long hourlySummaryRowsConsumed,
        long rawBoundaryRowsSummarized,
        int waitingSensors,
        int failedSensors,
        boolean bounded,
        Duration maximumRollupLag,
        Instant oldestCoveredUntil
) {
    public enum Status {
        NO_WORK,
        SUCCEEDED,
        BOUNDED,
        WAITING_FOR_HOURLY,
        PARTIAL_FAILURE
    }
}