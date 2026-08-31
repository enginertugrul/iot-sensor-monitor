package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.time.Instant;
import java.util.Objects;

public record SensorDataPurgeTierResult(
        Tier tier,
        Status status,
        Instant retentionBoundary,
        int operationsAttempted,
        int deleteBatchesAttempted,
        int deletionBatches,
        long rowsDeleted,
        int noExpiredRowsSensors,
        int waitingForHourlyCoverageSensors,
        int waitingForDailyCoverageSensors,
        int concurrentlyDeferredSensors,
        int failedSensors,
        boolean bounded
) {

    public SensorDataPurgeTierResult {
        Objects.requireNonNull(tier,"tier must not be null");
        Objects.requireNonNull(status,"status must not be null");
        Objects.requireNonNull(retentionBoundary,"retentionBoundary must not be null");

        if (operationsAttempted < 0
                || deleteBatchesAttempted < 0
                || deletionBatches < 0
                || rowsDeleted < 0
                || noExpiredRowsSensors < 0
                || waitingForHourlyCoverageSensors < 0
                || waitingForDailyCoverageSensors < 0
                || concurrentlyDeferredSensors < 0
                || failedSensors < 0) {

            throw new IllegalArgumentException("Purge result counters must not be negative");
        }

        if (deleteBatchesAttempted > operationsAttempted) {
            throw new IllegalArgumentException("deleteBatchesAttempted must not exceed operationsAttempted");
        }

        if (deletionBatches > deleteBatchesAttempted) {
            throw new IllegalArgumentException("deletionBatches must not exceed deleteBatchesAttempted");
        }

        if ((deletionBatches == 0) != (rowsDeleted == 0)) {
            throw new IllegalArgumentException("Deletion batches and deleted rows must either both be zero or both be positive");
        }

        if (status == Status.BOUNDED && !bounded) {
            throw new IllegalArgumentException("A bounded status requires bounded=true");
        }
    }



    public enum Tier {

        RAW_READINGS,
        HOURLY_SUMMARIES,
        DAILY_SUMMARIES
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