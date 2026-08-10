package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupSensorProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;





@Service
public class DailySensorRollupService {


    private final Logger logger = LoggerFactory.getLogger(DailySensorRollupService.class);

    private final SensorRepository sensorRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;
    private final DailySensorRollupBucketProcessor bucketProcessor;
    private final SensorDataLifecyclePolicy lifecyclePolicy;



    public DailySensorRollupService(SensorRepository sensorRepository, SensorRollupCheckpointRepository checkpointRepository, DailySensorRollupBucketProcessor bucketProcessor, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.sensorRepository = sensorRepository;
        this.checkpointRepository = checkpointRepository;
        this.bucketProcessor = bucketProcessor;
        this.lifecyclePolicy = lifecyclePolicy;
    }




    public DailyRollupRunResult rollUpClosedLocalDays(Instant eligibleBucketEnd) {

        Instant requiredEligibleBucketEnd = Objects.requireNonNull(eligibleBucketEnd, "eligibleBucketEnd must not be null");

        List<RollupSensorProjection> sensors = sensorRepository.findSensorsForRollup();

        RollupRunState run = new RollupRunState(lifecyclePolicy.getMaximumBucketsPerRun());

        catchUpClosedLocalDays(sensors, requiredEligibleBucketEnd, run);

        run.bounded = run.isBudgetExhausted() && run.hasUnfinishedCatchUp(sensors);



        Instant oldestCoveredUntil =
                checkpointRepository.findOldestCoveredUntilByStage(
                                RollupStage.HOURLY_TO_DAILY)
                        .orElse(null);



        return new DailyRollupRunResult(
                determineRunStatus(run),
                sensors.size(),
                run.maximumBuckets - run.remainingBudget,
                run.advancedBuckets,
                run.sourceRowsSummarized,
                run.hourlySummaryRowsConsumed,
                run.rawBoundaryRowsSummarized,
                run.waitingSensors.size(),
                run.failedSensors.size(),
                run.bounded,
                run.maximumRollupLag,
                oldestCoveredUntil);
    }







    private void catchUpClosedLocalDays(List<RollupSensorProjection> sensors, Instant eligibleBucketEnd, RollupRunState run) {

        do {
            run.madeProgress = false;

            for (RollupSensorProjection sensor : sensors) {
                if (run.isBudgetExhausted()) {
                    break;
                }

                if (!run.canAttemptCatchUp(sensor.getId())) {
                    continue;
                }

                attemptNextClosedLocalDay(sensor, eligibleBucketEnd, run);
            }
        } while (run.madeProgress && run.hasRemainingBudget());
    }



    private void attemptNextClosedLocalDay(RollupSensorProjection sensor, Instant eligibleBucketEnd, RollupRunState run) {

        try {

            DailyRollupBucketResult result = bucketProcessor.advanceNextClosedDay(sensor, eligibleBucketEnd);

            switch (result.status()) {

                case ADVANCED ->
                        run.recordAdvanced(sensor.getId(), result, eligibleBucketEnd);

                case UP_TO_DATE ->
                        run.recordUpToDate(sensor.getId(), result, eligibleBucketEnd);

                case WAITING_FOR_HOURLY -> {
                    run.recordWaiting(sensor.getId(), result, eligibleBucketEnd);

                    logger.debug(
                            "Daily rollup skipped sensorId={} localDate={} reason={} requiredHourlyCoveredUntil={} hourlyCoveredUntil={}",
                            sensor.getId(),
                            result.localDate(),
                            result.status(),
                            result.requiredHourlyCoveredUntil(),
                            result.hourlyCoveredUntil());
                }

            }

        } catch (RuntimeException exception) {
            run.recordAdvanceFailure(sensor.getId());

            logger.error(
                    "Daily rollup advance failed sensorId={} expectedBucketStart={} eligibleBucketEnd={}",
                    sensor.getId(),
                    run.expectedBucketStart(sensor.getId()),
                    eligibleBucketEnd,
                    exception);
        }
    }



    private static DailyRollupRunResult.Status determineRunStatus(RollupRunState run) {

        if (!run.failedSensors.isEmpty()) {
            return DailyRollupRunResult.Status.PARTIAL_FAILURE;
        }

        if (run.bounded) {
            return DailyRollupRunResult.Status.BOUNDED;
        }

        if (!run.waitingSensors.isEmpty()) {
            return DailyRollupRunResult.Status.WAITING_FOR_HOURLY;
        }

        if (run.advancedBuckets == 0) {
            return DailyRollupRunResult.Status.NO_WORK;
        }

        return DailyRollupRunResult.Status.SUCCEEDED;
    }



    private static boolean hasAnotherEligibleDay(DailyRollupBucketResult result, Instant eligibleBucketEnd) {

        Instant nextBucketEnd = nextBucketEndAfterCoverage(result);

        return nextBucketEnd != null
                && !nextBucketEnd.isAfter(eligibleBucketEnd);
    }



    private static Duration calculateRollupLag(DailyRollupBucketResult result, Instant eligibleBucketEnd) {

        Instant nextBucketEnd = nextBucketEndAfterCoverage(result);

        if (nextBucketEnd == null
                || nextBucketEnd.isAfter(eligibleBucketEnd)) {
            return Duration.ZERO;
        }

        return Duration.between(
                result.coveredUntil(),
                eligibleBucketEnd);
    }



    private static Instant nextBucketEndAfterCoverage(DailyRollupBucketResult result) {

        if (result.coveredUntil() == null
                || result.timeZoneId() == null) {
            return null;
        }

        ZoneId timeZone = ZoneId.of(result.timeZoneId());

        LocalDate nextLocalDate = result.coveredUntil().atZone(timeZone).toLocalDate();

        Instant expectedBucketStart = nextLocalDate.atStartOfDay(timeZone).toInstant();

        if (!expectedBucketStart.equals(result.coveredUntil())) {
            return null;
        }

        return nextLocalDate.plusDays(1)
                .atStartOfDay(timeZone)
                .toInstant();
    }



    private static final class RollupRunState {

        private final int maximumBuckets;
        private int remainingBudget;

        private int advancedBuckets;
        private long sourceRowsSummarized;
        private long hourlySummaryRowsConsumed;
        private long rawBoundaryRowsSummarized;

        private boolean madeProgress;
        private boolean bounded;

        private Duration maximumRollupLag = Duration.ZERO;

        private final Set<Long> caughtUpSensors = new HashSet<>();
        private final Set<Long> waitingSensors = new HashSet<>();
        private final Set<Long> failedSensors = new HashSet<>();

        private final Map<Long,DailyRollupBucketResult> latestProgress =
                new HashMap<>();

        private RollupRunState(int maximumBuckets) {
            this.maximumBuckets = maximumBuckets;
            this.remainingBudget = maximumBuckets;
        }



        private boolean hasRemainingBudget() {
            return remainingBudget > 0;
        }



        private boolean isBudgetExhausted() {
            return remainingBudget == 0;
        }



        private boolean canAttemptCatchUp(Long sensorId) {
            return !caughtUpSensors.contains(sensorId)
                    && !waitingSensors.contains(sensorId)
                    && !failedSensors.contains(sensorId);
        }



        private boolean hasUnfinishedCatchUp(List<RollupSensorProjection> sensors) {

            for (RollupSensorProjection sensor : sensors) {
                if (canAttemptCatchUp(sensor.getId())) {
                    return true;
                }
            }

            return false;
        }



        private void recordAdvanced(Long sensorId, DailyRollupBucketResult result, Instant eligibleBucketEnd) {

            remainingBudget--;
            advancedBuckets++;
            sourceRowsSummarized += result.sourceSampleCount();
            hourlySummaryRowsConsumed += result.hourlySummaryRows();
            rawBoundaryRowsSummarized += result.rawBoundarySampleCount();

            recordProgress(sensorId,result,eligibleBucketEnd);
            madeProgress = true;

            if (!hasAnotherEligibleDay(result,eligibleBucketEnd)) {
                caughtUpSensors.add(sensorId);
            }
        }



        private void recordUpToDate(Long sensorId, DailyRollupBucketResult result, Instant eligibleBucketEnd) {
            recordProgress(sensorId,result,eligibleBucketEnd);
            caughtUpSensors.add(sensorId);
        }



        private void recordWaiting(Long sensorId, DailyRollupBucketResult result, Instant eligibleBucketEnd) {
            recordProgress(sensorId,result,eligibleBucketEnd);
            waitingSensors.add(sensorId);
        }





        private void recordAdvanceFailure(Long sensorId) {
            remainingBudget--;
            failedSensors.add(sensorId);
        }



        private void recordProgress(Long sensorId, DailyRollupBucketResult result, Instant eligibleBucketEnd) {

            latestProgress.put(sensorId,result);

            Duration rollupLag = calculateRollupLag(result,eligibleBucketEnd);

            if (rollupLag.compareTo(maximumRollupLag) > 0) {
                maximumRollupLag = rollupLag;
            }
        }



        private Instant expectedBucketStart(Long sensorId) {

            DailyRollupBucketResult previousProgress =
                    latestProgress.get(sensorId);

            return previousProgress == null
                    ? null
                    : previousProgress.coveredUntil();
        }
    }

}