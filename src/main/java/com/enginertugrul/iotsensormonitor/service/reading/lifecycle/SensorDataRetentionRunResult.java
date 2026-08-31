package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record SensorDataRetentionRunResult(
        Status status,
        int sensorCount,
        SensorDataPurgeTierResult rawReadings,
        SensorDataPurgeTierResult hourlySummaries,
        SensorDataPurgeTierResult dailySummaries,
        Instant oldestRawToHourlyCoveredUntil,
        Instant oldestHourlyToDailyCoveredUntil
) {

    public SensorDataRetentionRunResult {
        Objects.requireNonNull(status,"status must not be null");
        Objects.requireNonNull(rawReadings,"rawReadings must not be null");
        Objects.requireNonNull(hourlySummaries,"hourlySummaries must not be null");
        Objects.requireNonNull(dailySummaries,"dailySummaries must not be null");

        if (sensorCount < 0) {
            throw new IllegalArgumentException("sensorCount must not be negative");
        }
    }



    public int totalOperationsAttempted() {
        return Math.addExact(
                Math.addExact(rawReadings.operationsAttempted(),hourlySummaries.operationsAttempted()),
                dailySummaries.operationsAttempted());
    }



    public int totalDeleteBatchesAttempted() {
        return Math.addExact(
                Math.addExact(rawReadings.deleteBatchesAttempted(),hourlySummaries.deleteBatchesAttempted()),
                dailySummaries.deleteBatchesAttempted());
    }



    public int totalDeletionBatches() {
        return Math.addExact(
                Math.addExact(rawReadings.deletionBatches(),hourlySummaries.deletionBatches()),
                dailySummaries.deletionBatches());
    }



    public long totalRowsDeleted() {
        return Math.addExact(
                Math.addExact(rawReadings.rowsDeleted(),hourlySummaries.rowsDeleted()),
                dailySummaries.rowsDeleted());
    }



    public enum Status {

        NO_WORK,
        SUCCEEDED,
        BOUNDED,
        WAITING_FOR_COVERAGE,
        RETRY_PENDING,
        PARTIAL_FAILURE
    }
}