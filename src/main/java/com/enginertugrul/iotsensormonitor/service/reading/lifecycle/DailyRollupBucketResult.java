package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.time.Instant;
import java.time.LocalDate;

public record DailyRollupBucketResult(
        Status status,
        long sensorId,
        LocalDate localDate,
        String timeZoneId,
        Instant bucketStart,
        Instant bucketEnd,
        Instant coverageStartedAt,
        Instant coveredUntil,
        Instant requiredHourlyCoveredUntil,
        Instant hourlyCoveredUntil,
        long sourceSampleCount,
        int hourlySummaryRows,
        long rawBoundarySampleCount
) {

    public enum Status {
        ADVANCED,
        REFRESHED,
        UP_TO_DATE,
        WAITING_FOR_HOURLY,
        NOT_COVERED
    }

}