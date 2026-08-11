package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;




@Service
public class SensorDataPurgeBatchProcessor {



    private static final int MAXIMUM_BATCH_SIZE = 10_000;

    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;
    private final DailySensorSummaryRepository dailySensorSummaryRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;



    public SensorDataPurgeBatchProcessor(SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, DailySensorSummaryRepository dailySensorSummaryRepository, SensorRollupCheckpointRepository checkpointRepository) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.dailySensorSummaryRepository = dailySensorSummaryRepository;
        this.checkpointRepository = checkpointRepository;
    }



    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SensorDataPurgeBatchResult purgeRawReadings(Long sensorId, Instant retentionBoundaryExclusive, int batchSize) {

        long requiredSensorId = requireSensorId(sensorId);
        Instant requiredRetentionBoundary = requireUtcHourBoundary(retentionBoundaryExclusive,"retentionBoundaryExclusive");
        int requiredBatchSize = requireBatchSize(batchSize);

        if (!sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(requiredSensorId,requiredRetentionBoundary)) {
            return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,false);
        }

        Optional<SensorRollupCheckpoint> hourlyCheckpoint =
                checkpointRepository.findBySensorIdAndStage(requiredSensorId,RollupStage.RAW_TO_HOURLY);

        Optional<SensorRollupCheckpoint> dailyCheckpoint =
                checkpointRepository.findBySensorIdAndStage(requiredSensorId,RollupStage.HOURLY_TO_DAILY);

        SensorDataPurgeBatchResult.CoverageBlocker coverageBlocker =
                determineRawCoverageBlocker(hourlyCheckpoint,dailyCheckpoint,requiredRetentionBoundary);

        if (hourlyCheckpoint.isEmpty() || dailyCheckpoint.isEmpty()) {
            return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,false,coverageBlocker);
        }

        if (!sensorReadingRepository.existsEligibleForRetentionPurge(requiredSensorId,requiredRetentionBoundary)) {
            if (!sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(requiredSensorId,requiredRetentionBoundary)) {
                return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,false);
            }

            if (coverageBlocker != SensorDataPurgeBatchResult.CoverageBlocker.NONE) {
                return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,false,coverageBlocker);
            }

            throw new IllegalStateException("Expired raw readings exist inside verified rollup coverage but none are eligible for deletion");
        }

        int rowsDeleted = requireValidDeletedRowCount(
                sensorReadingRepository.deleteOldestEligibleRetentionBatch(
                        requiredSensorId,
                        requiredRetentionBoundary,
                        requiredBatchSize),
                requiredBatchSize);

        boolean moreEligibleRows =
                sensorReadingRepository.existsEligibleForRetentionPurge(
                        requiredSensorId,
                        requiredRetentionBoundary);

        if (rowsDeleted > 0) {
            SensorDataPurgeBatchResult.CoverageBlocker remainingCoverageBlocker =
                    determineRemainingCoverageBlocker(
                            moreEligibleRows,
                            coverageBlocker,
                            sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(
                                    requiredSensorId,
                                    requiredRetentionBoundary));

            return SensorDataPurgeBatchResult.deleted(
                    requiredSensorId,
                    rowsDeleted,
                    moreEligibleRows,
                    remainingCoverageBlocker);
        }

        if (moreEligibleRows) {
            return SensorDataPurgeBatchResult.deferredByConcurrentWork(requiredSensorId);
        }

        if (!sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(requiredSensorId,requiredRetentionBoundary)) {
            return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,true);
        }

        if (coverageBlocker != SensorDataPurgeBatchResult.CoverageBlocker.NONE) {
            return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,true,coverageBlocker);
        }

        throw new IllegalStateException("Raw deletion returned no rows although expired readings remain inside verified coverage");
    }






    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SensorDataPurgeBatchResult purgeHourlySummaries(Long sensorId, Instant retentionBoundaryInclusive, int batchSize) {

        long requiredSensorId = requireSensorId(sensorId);
        Instant requiredRetentionBoundary = requireUtcHourBoundary(retentionBoundaryInclusive,"retentionBoundaryInclusive");
        int requiredBatchSize = requireBatchSize(batchSize);

        if (!hourlySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(requiredSensorId,requiredRetentionBoundary)) {
            return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,false);
        }

        Optional<SensorRollupCheckpoint> dailyCheckpoint =
                checkpointRepository.findBySensorIdAndStage(requiredSensorId,RollupStage.HOURLY_TO_DAILY);

        SensorDataPurgeBatchResult.CoverageBlocker coverageBlocker =
                determineHourlyCoverageBlocker(dailyCheckpoint,requiredRetentionBoundary);

        if (dailyCheckpoint.isEmpty()) {
            return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,false,coverageBlocker);
        }

        if (!hourlySensorSummaryRepository.existsEligibleForRetentionPurge(requiredSensorId,requiredRetentionBoundary)) {
            if (!hourlySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(requiredSensorId,requiredRetentionBoundary)) {
                return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,false);
            }

            if (coverageBlocker != SensorDataPurgeBatchResult.CoverageBlocker.NONE) {
                return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,false,coverageBlocker);
            }

            throw new IllegalStateException("Expired hourly summaries exist inside verified daily coverage but none are eligible for deletion");
        }

        int rowsDeleted = requireValidDeletedRowCount(
                hourlySensorSummaryRepository.deleteOldestEligibleRetentionBatch(
                        requiredSensorId,
                        requiredRetentionBoundary,
                        requiredBatchSize),
                requiredBatchSize);

        boolean moreEligibleRows =
                hourlySensorSummaryRepository.existsEligibleForRetentionPurge(
                        requiredSensorId,
                        requiredRetentionBoundary);

        if (rowsDeleted > 0) {
            SensorDataPurgeBatchResult.CoverageBlocker remainingCoverageBlocker =
                    determineRemainingCoverageBlocker(
                            moreEligibleRows,
                            coverageBlocker,
                            hourlySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(
                                    requiredSensorId,
                                    requiredRetentionBoundary));

            return SensorDataPurgeBatchResult.deleted(
                    requiredSensorId,
                    rowsDeleted,
                    moreEligibleRows,
                    remainingCoverageBlocker);
        }

        if (moreEligibleRows) {
            return SensorDataPurgeBatchResult.deferredByConcurrentWork(requiredSensorId);
        }

        if (!hourlySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(requiredSensorId,requiredRetentionBoundary)) {
            return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,true);
        }

        if (coverageBlocker != SensorDataPurgeBatchResult.CoverageBlocker.NONE) {
            return SensorDataPurgeBatchResult.waitingForCoverage(requiredSensorId,true,coverageBlocker);
        }

        throw new IllegalStateException("Hourly deletion returned no rows although expired summaries remain inside verified coverage");
    }






    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SensorDataPurgeBatchResult purgeDailySummaries(Long sensorId, Instant retentionBoundaryInclusive, int batchSize) {

        long requiredSensorId = requireSensorId(sensorId);
        Instant requiredRetentionBoundary = Objects.requireNonNull(retentionBoundaryInclusive,"retentionBoundaryInclusive must not be null");
        int requiredBatchSize = requireBatchSize(batchSize);

        if (!dailySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(requiredSensorId,requiredRetentionBoundary)) {
            return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,false);
        }

        int rowsDeleted = requireValidDeletedRowCount(
                dailySensorSummaryRepository.deleteOldestRetentionBatch(
                        requiredSensorId,
                        requiredRetentionBoundary,
                        requiredBatchSize),
                requiredBatchSize);

        boolean moreEligibleRows =
                dailySensorSummaryRepository.existsBySensorIdAndBucketEndLessThanEqual(
                        requiredSensorId,
                        requiredRetentionBoundary);

        if (rowsDeleted > 0) {
            return SensorDataPurgeBatchResult.deleted(
                    requiredSensorId,
                    rowsDeleted,
                    moreEligibleRows,
                    SensorDataPurgeBatchResult.CoverageBlocker.NONE);
        }

        if (moreEligibleRows) {
            return SensorDataPurgeBatchResult.deferredByConcurrentWork(requiredSensorId);
        }

        return SensorDataPurgeBatchResult.noExpiredRows(requiredSensorId,true);
    }






    private static SensorDataPurgeBatchResult.CoverageBlocker determineRawCoverageBlocker(
            Optional<SensorRollupCheckpoint> rawCheckpoint,
            Optional<SensorRollupCheckpoint> dailyCheckpoint,
            Instant retentionBoundary
    ) {
        boolean waitingForHourlyCoverage = rawCheckpoint.isEmpty()
                || rawCheckpoint.get().getCoveredUntil().isBefore(retentionBoundary);

        boolean waitingForDailyCoverage = dailyCheckpoint.isEmpty()
                || utcHourAtOrBefore(dailyCheckpoint.get().getCoveredUntil()).isBefore(retentionBoundary);

        return coverageBlocker(waitingForHourlyCoverage,waitingForDailyCoverage);
    }






    private static SensorDataPurgeBatchResult.CoverageBlocker determineHourlyCoverageBlocker(
            Optional<SensorRollupCheckpoint> dailyCheckpoint,
            Instant retentionBoundary
    ) {
        boolean waitingForDailyCoverage = dailyCheckpoint.isEmpty()
                || utcHourAtOrBefore(dailyCheckpoint.get().getCoveredUntil()).isBefore(retentionBoundary);

        return waitingForDailyCoverage
                ? SensorDataPurgeBatchResult.CoverageBlocker.HOURLY_TO_DAILY
                : SensorDataPurgeBatchResult.CoverageBlocker.NONE;
    }





    private static SensorDataPurgeBatchResult.CoverageBlocker determineRemainingCoverageBlocker(
            boolean moreEligibleRows,
            SensorDataPurgeBatchResult.CoverageBlocker coverageBlocker,
            boolean expiredRowsRemain
    ) {
        if (moreEligibleRows || !expiredRowsRemain) {
            return SensorDataPurgeBatchResult.CoverageBlocker.NONE;
        }

        return coverageBlocker;
    }





    private static SensorDataPurgeBatchResult.CoverageBlocker coverageBlocker(
            boolean waitingForHourlyCoverage,
            boolean waitingForDailyCoverage
    ) {
        if (waitingForHourlyCoverage && waitingForDailyCoverage) {
            return SensorDataPurgeBatchResult.CoverageBlocker.BOTH;
        }

        if (waitingForHourlyCoverage) {
            return SensorDataPurgeBatchResult.CoverageBlocker.RAW_TO_HOURLY;
        }

        if (waitingForDailyCoverage) {
            return SensorDataPurgeBatchResult.CoverageBlocker.HOURLY_TO_DAILY;
        }

        return SensorDataPurgeBatchResult.CoverageBlocker.NONE;
    }





    private static long requireSensorId(Long sensorId) {
        Long requiredSensorId = Objects.requireNonNull(sensorId,"sensorId must not be null");

        if (requiredSensorId <= 0) {
            throw new IllegalArgumentException("sensorId must be positive");
        }

        return requiredSensorId;
    }





    private static Instant requireUtcHourBoundary(Instant value, String fieldName) {
        Instant requiredValue = Objects.requireNonNull(value,fieldName + " must not be null");

        if (!requiredValue.equals(requiredValue.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException(fieldName + " must be aligned to a UTC hour");
        }

        return requiredValue;
    }




    private static Instant utcHourAtOrBefore(Instant value) {
        return value.truncatedTo(ChronoUnit.HOURS);
    }





    private static int requireBatchSize(int batchSize) {
        if (batchSize < 1 || batchSize > MAXIMUM_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be between 1 and " + MAXIMUM_BATCH_SIZE);
        }

        return batchSize;
    }





    private static int requireValidDeletedRowCount(int rowsDeleted, int batchSize) {
        if (rowsDeleted < 0 || rowsDeleted > batchSize) {
            throw new IllegalStateException("Deleted row count must be between zero and batchSize");
        }

        return rowsDeleted;
    }
}