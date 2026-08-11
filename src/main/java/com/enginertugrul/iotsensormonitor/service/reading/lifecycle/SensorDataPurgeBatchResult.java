package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import java.util.Objects;

public record SensorDataPurgeBatchResult(
        Status status,
        long sensorId,
        int rowsDeleted,
        boolean deleteAttempted,
        boolean moreEligibleRows,
        CoverageBlocker remainingCoverageBlocker
) {

    public SensorDataPurgeBatchResult {
        Objects.requireNonNull(status,"status must not be null");
        Objects.requireNonNull(remainingCoverageBlocker,"remainingCoverageBlocker must not be null");

        if (sensorId <= 0) {
            throw new IllegalArgumentException("sensorId must be positive");
        }

        if (rowsDeleted < 0) {
            throw new IllegalArgumentException("rowsDeleted must not be negative");
        }

        if ((status == Status.DELETED) != (rowsDeleted > 0)) {
            throw new IllegalArgumentException("Only a deleted result may contain deleted rows");
        }

        if (status == Status.DELETED && !deleteAttempted) {
            throw new IllegalArgumentException("A deleted result must represent an attempted delete");
        }

        if (moreEligibleRows && status != Status.DELETED) {
            throw new IllegalArgumentException("Only a deleted result may report more eligible rows");
        }

        if (status == Status.DEFERRED_BY_CONCURRENT_WORK && !deleteAttempted) {
            throw new IllegalArgumentException("A concurrent-work result must follow an attempted delete");
        }

        if (status == Status.WAITING_FOR_COVERAGE
                && remainingCoverageBlocker == CoverageBlocker.NONE) {
            throw new IllegalArgumentException("A waiting result must identify its coverage blocker");
        }

        if (status != Status.WAITING_FOR_COVERAGE
                && status != Status.DELETED
                && remainingCoverageBlocker != CoverageBlocker.NONE) {
            throw new IllegalArgumentException("This result status cannot contain a coverage blocker");
        }

        if (moreEligibleRows && remainingCoverageBlocker != CoverageBlocker.NONE) {
            throw new IllegalArgumentException("A result with eligible rows cannot already be waiting for coverage");
        }
    }



    public static SensorDataPurgeBatchResult deleted(long sensorId, int rowsDeleted, boolean moreEligibleRows, CoverageBlocker remainingCoverageBlocker) {
        return new SensorDataPurgeBatchResult(
                Status.DELETED,
                sensorId,
                rowsDeleted,
                true,
                moreEligibleRows,
                remainingCoverageBlocker);
    }



    public static SensorDataPurgeBatchResult noExpiredRows(long sensorId, boolean deleteAttempted) {
        return new SensorDataPurgeBatchResult(
                Status.NO_EXPIRED_ROWS,
                sensorId,
                0,
                deleteAttempted,
                false,
                CoverageBlocker.NONE);
    }



    public static SensorDataPurgeBatchResult waitingForCoverage(long sensorId, boolean deleteAttempted, CoverageBlocker coverageBlocker) {
        return new SensorDataPurgeBatchResult(
                Status.WAITING_FOR_COVERAGE,
                sensorId,
                0,
                deleteAttempted,
                false,
                coverageBlocker);
    }



    public static SensorDataPurgeBatchResult deferredByConcurrentWork(long sensorId) {
        return new SensorDataPurgeBatchResult(
                Status.DEFERRED_BY_CONCURRENT_WORK,
                sensorId,
                0,
                true,
                false,
                CoverageBlocker.NONE);
    }



    public enum Status {

        DELETED,
        NO_EXPIRED_ROWS,
        WAITING_FOR_COVERAGE,
        DEFERRED_BY_CONCURRENT_WORK
    }



    public enum CoverageBlocker {

        NONE,
        RAW_TO_HOURLY,
        HOURLY_TO_DAILY,
        BOTH
    }
}