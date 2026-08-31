package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataPurgeBatchResult.CoverageBlocker;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataPurgeTierResult.Tier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;




@Service
public class SensorDataRetentionService {



    private final Logger logger = LoggerFactory.getLogger(SensorDataRetentionService.class);

    private final SensorRepository sensorRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;
    private final SensorDataPurgeBatchProcessor batchProcessor;
    private final SensorDataLifecyclePolicy lifecyclePolicy;

    public SensorDataRetentionService(SensorRepository sensorRepository, SensorRollupCheckpointRepository checkpointRepository, SensorDataPurgeBatchProcessor batchProcessor, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.sensorRepository = sensorRepository;
        this.checkpointRepository = checkpointRepository;
        this.batchProcessor = batchProcessor;
        this.lifecyclePolicy = lifecyclePolicy;
    }



    public SensorDataRetentionRunResult purgeExpiredData(Instant currentTime) {

        Objects.requireNonNull(currentTime,"currentTime must not be null");

        List<Long> sensorIds = sensorRepository.findSensorIdsWithReadingHistory();

        Instant rawRetentionBoundary = currentTime
                .minus(lifecyclePolicy.getRawRetention())
                .truncatedTo(ChronoUnit.HOURS);

        Instant hourlyRetentionBoundary = currentTime
                .minus(lifecyclePolicy.getHourlyRetention())
                .truncatedTo(ChronoUnit.HOURS);

        Instant dailyRetentionBoundary =
                currentTime.minus(lifecyclePolicy.getDailyRetention());

        int batchSize = lifecyclePolicy.getDeleteBatchSize();

        SensorDataPurgeTierResult rawResult = purgeTier(
                Tier.RAW_READINGS,
                sensorIds,
                currentTime,
                rawRetentionBoundary,
                sensorId -> batchProcessor.purgeRawReadings(sensorId,rawRetentionBoundary,batchSize));

        SensorDataPurgeTierResult hourlyResult = purgeTier(
                Tier.HOURLY_SUMMARIES,
                sensorIds,
                currentTime,
                hourlyRetentionBoundary,
                sensorId -> batchProcessor.purgeHourlySummaries(sensorId,hourlyRetentionBoundary,batchSize));

        SensorDataPurgeTierResult dailyResult = purgeTier(
                Tier.DAILY_SUMMARIES,
                sensorIds,
                currentTime,
                dailyRetentionBoundary,
                sensorId -> batchProcessor.purgeDailySummaries(sensorId,dailyRetentionBoundary,batchSize));

        Instant oldestRawToHourlyCoveredUntil =
                checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY)
                        .orElse(null);

        Instant oldestHourlyToDailyCoveredUntil =
                checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY)
                        .orElse(null);

        return new SensorDataRetentionRunResult(
                determineRunStatus(rawResult,hourlyResult,dailyResult),
                sensorIds.size(),
                rawResult,
                hourlyResult,
                dailyResult,
                oldestRawToHourlyCoveredUntil,
                oldestHourlyToDailyCoveredUntil);
    }



    private SensorDataPurgeTierResult purgeTier(
            Tier tier,
            List<Long> sensorIds,
            Instant currentTime,
            Instant retentionBoundary,
            PurgeBatchOperation operation
    ) {

        TierRunState run = new TierRunState(lifecyclePolicy.getMaximumDeleteBatchesPerTierPerRun());

        Deque<Long> pendingSensorIds = new ArrayDeque<>(
                rotateSensorIds(sensorIds,currentTime,tier));

        while (run.hasRemainingDeleteBudget() && !pendingSensorIds.isEmpty()) {
            Long sensorId = pendingSensorIds.removeFirst();
            boolean deleteBudgetConsumed = false;

            run.recordOperation();

            try {
                SensorDataPurgeBatchResult result = operation.purge(sensorId);

                if (result.sensorId() != sensorId) {
                    throw new IllegalStateException("Purge batch result belongs to a different sensor");
                }

                if (result.deleteAttempted()) {
                    run.recordDeleteBatchAttempt();
                    deleteBudgetConsumed = true;
                }

                switch (result.status()) {
                    case DELETED -> {
                        run.recordSuccessfulDeletion(result.rowsDeleted());

                        if (result.moreEligibleRows()) {
                            pendingSensorIds.addLast(sensorId);
                        } else if (result.remainingCoverageBlocker() != CoverageBlocker.NONE) {
                            run.recordWaiting(result.remainingCoverageBlocker());

                            logger.debug(
                                    "Sensor data purge waiting after safe rows were deleted tier={} sensorId={} coverageBlocker={} retentionBoundary={}",
                                    tier,
                                    sensorId,
                                    result.remainingCoverageBlocker(),
                                    retentionBoundary);
                        }
                    }

                    case NO_EXPIRED_ROWS ->
                            run.recordNoExpiredRows();

                    case WAITING_FOR_COVERAGE -> {
                        run.recordWaiting(result.remainingCoverageBlocker());

                        logger.debug(
                                "Sensor data purge waiting tier={} sensorId={} coverageBlocker={} retentionBoundary={}",
                                tier,
                                sensorId,
                                result.remainingCoverageBlocker(),
                                retentionBoundary);
                    }

                    case DEFERRED_BY_CONCURRENT_WORK -> {
                        run.recordConcurrentDeferral();

                        logger.debug(
                                "Sensor data purge deferred tier={} sensorId={} reason={} retentionBoundary={} retry=NEXT_SCHEDULED_RUN",
                                tier,
                                sensorId,
                                result.status(),
                                retentionBoundary);
                    }
                }
            } catch (RuntimeException exception) {
                run.recordFailure(!deleteBudgetConsumed);

                logger.error(
                        "Sensor data purge failed tier={} sensorId={} retentionBoundary={} retry=NEXT_SCHEDULED_RUN",
                        tier,
                        sensorId,
                        retentionBoundary,
                        exception);
            }
        }

        boolean bounded = !pendingSensorIds.isEmpty() && !run.hasRemainingDeleteBudget();

        return run.toResult(tier,retentionBoundary,bounded);
    }



    private List<Long> rotateSensorIds(List<Long> sensorIds, Instant currentTime, Tier tier) {
        if (sensorIds.size() < 2) {
            return new ArrayList<>(sensorIds);
        }

        long intervalSeconds = Math.max(1,lifecyclePolicy.getPurgeInterval().getSeconds());

        long runSlot = Math.floorDiv(currentTime.getEpochSecond(),intervalSeconds);

        int startIndex = (int) Math.floorMod(
                runSlot + tier.ordinal(),
                (long) sensorIds.size());

        List<Long> rotatedSensorIds = new ArrayList<>(sensorIds.size());

        rotatedSensorIds.addAll(sensorIds.subList(startIndex,sensorIds.size()));
        rotatedSensorIds.addAll(sensorIds.subList(0,startIndex));

        return rotatedSensorIds;
    }



    private static SensorDataRetentionRunResult.Status determineRunStatus(
            SensorDataPurgeTierResult rawResult,
            SensorDataPurgeTierResult hourlyResult,
            SensorDataPurgeTierResult dailyResult
    ) {
        if (hasStatus(
                SensorDataPurgeTierResult.Status.PARTIAL_FAILURE,
                rawResult,
                hourlyResult,
                dailyResult)) {

            return SensorDataRetentionRunResult.Status.PARTIAL_FAILURE;
        }

        if (hasStatus(
                SensorDataPurgeTierResult.Status.BOUNDED,
                rawResult,
                hourlyResult,
                dailyResult)) {

            return SensorDataRetentionRunResult.Status.BOUNDED;
        }

        long rowsDeleted = Math.addExact(
                Math.addExact(rawResult.rowsDeleted(),hourlyResult.rowsDeleted()),
                dailyResult.rowsDeleted());

        if (rowsDeleted > 0) {
            return SensorDataRetentionRunResult.Status.SUCCEEDED;
        }

        if (hasStatus(
                SensorDataPurgeTierResult.Status.RETRY_PENDING,
                rawResult,
                hourlyResult,
                dailyResult)) {

            return SensorDataRetentionRunResult.Status.RETRY_PENDING;
        }

        if (hasStatus(
                SensorDataPurgeTierResult.Status.WAITING_FOR_COVERAGE,
                rawResult,
                hourlyResult,
                dailyResult)) {

            return SensorDataRetentionRunResult.Status.WAITING_FOR_COVERAGE;
        }

        return SensorDataRetentionRunResult.Status.NO_WORK;
    }



    private static boolean hasStatus(
            SensorDataPurgeTierResult.Status status,
            SensorDataPurgeTierResult rawResult,
            SensorDataPurgeTierResult hourlyResult,
            SensorDataPurgeTierResult dailyResult
    ) {
        return rawResult.status() == status
                || hourlyResult.status() == status
                || dailyResult.status() == status;
    }



    @FunctionalInterface
    private interface PurgeBatchOperation {

        SensorDataPurgeBatchResult purge(Long sensorId);
    }



    private static final class TierRunState {

        private int remainingDeleteBudget;
        private int operationsAttempted;
        private int deleteBatchesAttempted;
        private int deletionBatches;
        private long rowsDeleted;

        private int noExpiredRowsSensors;
        private int waitingForHourlyCoverageSensors;
        private int waitingForDailyCoverageSensors;
        private int concurrentlyDeferredSensors;
        private int failedSensors;

        private TierRunState(int maximumDeleteBatches) {
            this.remainingDeleteBudget = maximumDeleteBatches;
        }



        private boolean hasRemainingDeleteBudget() {
            return remainingDeleteBudget > 0;
        }



        private void recordOperation() {
            operationsAttempted++;
        }



        private void recordDeleteBatchAttempt() {
            consumeDeleteBudget();
            deleteBatchesAttempted++;
        }



        private void recordSuccessfulDeletion(int deletedRows) {
            deletionBatches++;
            rowsDeleted = Math.addExact(rowsDeleted,deletedRows);
        }



        private void recordNoExpiredRows() {
            noExpiredRowsSensors++;
        }



        private void recordWaiting(CoverageBlocker coverageBlocker) {
            switch (coverageBlocker) {
                case RAW_TO_HOURLY ->
                        waitingForHourlyCoverageSensors++;

                case HOURLY_TO_DAILY ->
                        waitingForDailyCoverageSensors++;

                case BOTH -> {
                    waitingForHourlyCoverageSensors++;
                    waitingForDailyCoverageSensors++;
                }

                case NONE ->
                        throw new IllegalArgumentException("Waiting purge result must identify a coverage blocker");
            }
        }



        private void recordConcurrentDeferral() {
            concurrentlyDeferredSensors++;
        }



        private void recordFailure(boolean consumeDeleteBudget) {
            if (consumeDeleteBudget) {
                consumeDeleteBudget();
            }

            failedSensors++;
        }



        private void consumeDeleteBudget() {
            if (!hasRemainingDeleteBudget()) {
                throw new IllegalStateException("Delete batch budget is already exhausted");
            }

            remainingDeleteBudget--;
        }



        private SensorDataPurgeTierResult toResult(Tier tier, Instant retentionBoundary, boolean bounded) {
            return new SensorDataPurgeTierResult(
                    tier,
                    determineStatus(bounded),
                    retentionBoundary,
                    operationsAttempted,
                    deleteBatchesAttempted,
                    deletionBatches,
                    rowsDeleted,
                    noExpiredRowsSensors,
                    waitingForHourlyCoverageSensors,
                    waitingForDailyCoverageSensors,
                    concurrentlyDeferredSensors,
                    failedSensors,
                    bounded);
        }



        private SensorDataPurgeTierResult.Status determineStatus(boolean bounded) {
            if (failedSensors > 0) {
                return SensorDataPurgeTierResult.Status.PARTIAL_FAILURE;
            }

            if (bounded) {
                return SensorDataPurgeTierResult.Status.BOUNDED;
            }

            if (rowsDeleted > 0) {
                return SensorDataPurgeTierResult.Status.SUCCEEDED;
            }

            if (concurrentlyDeferredSensors > 0) {
                return SensorDataPurgeTierResult.Status.RETRY_PENDING;
            }

            if (waitingForHourlyCoverageSensors > 0
                    || waitingForDailyCoverageSensors > 0) {

                return SensorDataPurgeTierResult.Status.WAITING_FOR_COVERAGE;
            }

            return SensorDataPurgeTierResult.Status.NO_WORK;
        }
    }
}