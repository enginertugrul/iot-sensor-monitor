package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupSensorProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

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




    public DailyRollupRunResult rollUpClosedLocalDays(Instant eligibleBucketEnd, Instant hourlyEligibleCoveredUntil) {

        Instant requiredEligibleBucketEnd = Objects.requireNonNull(eligibleBucketEnd, "eligibleBucketEnd must not be null");

        Instant requiredHourlyEligibleCoveredUntil = Objects.requireNonNull(hourlyEligibleCoveredUntil, "hourlyEligibleCoveredUntil must not be null");

        List<RollupSensorProjection> sensors = sensorRepository.findSensorsForRollup();

        RollupRunState run = new RollupRunState(lifecyclePolicy.getMaximumBucketsPerRun());

        catchUpClosedLocalDays(sensors, requiredEligibleBucketEnd, run);

        run.bounded = run.isBudgetExhausted() && run.hasUnfinishedCatchUp(sensors);

        refreshRecentlyCoveredLocalDays(
                sensors,
                requiredEligibleBucketEnd,
                requiredHourlyEligibleCoveredUntil,
                run);

        Instant oldestCoveredUntil =
                checkpointRepository.findOldestCoveredUntilByStage(
                                RollupStage.HOURLY_TO_DAILY)
                        .orElse(null);



        return new DailyRollupRunResult(
                determineRunStatus(run),
                sensors.size(),
                run.maximumBuckets - run.remainingBudget,
                run.advancedBuckets,
                run.refreshedBuckets,
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

                case REFRESHED, NOT_COVERED -> throw new IllegalStateException("Unexpected daily advance result " + result.status());

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





    private void refreshRecentlyCoveredLocalDays(
            List<RollupSensorProjection> sensors,
            Instant eligibleBucketEnd,
            Instant hourlyEligibleCoveredUntil,
            RollupRunState run
    ) {

        Instant refreshThreshold =
                hourlyEligibleCoveredUntil
                        .minus(lifecyclePolicy.getHourlyRollupTrailingWindow())
                        .minus(lifecyclePolicy.getDailyRollupInterval());

        for (RollupSensorProjection sensor : sensors) {
            ZoneId timeZone =
                    ZoneId.of(sensor.getTimezone());

            LocalDate newestCandidate = hourlyEligibleCoveredUntil.atZone(timeZone).toLocalDate();

            LocalDate oldestCandidate = refreshThreshold.atZone(timeZone).toLocalDate();

            for (
                    LocalDate localDate = newestCandidate;
                    !localDate.isBefore(oldestCandidate);
                    localDate = localDate.minusDays(1)
            ) {

                Instant bucketStart = localDate.atStartOfDay(timeZone).toInstant();

                Instant bucketEnd = localDate.plusDays(1)
                                .atStartOfDay(timeZone)
                                .toInstant();

                if (!run.isRefreshCandidate(
                        sensor.getId(),
                        localDate,
                        bucketStart,
                        bucketEnd,
                        refreshThreshold,
                        hourlyEligibleCoveredUntil,
                        eligibleBucketEnd)) {

                    continue;
                }

                if (run.isBudgetExhausted()) {
                    run.bounded = true;
                    return;
                }

                run.recordRefreshAttempt();

                try {
                    DailyRollupBucketResult result = bucketProcessor.refreshCoveredDay(sensor, localDate, eligibleBucketEnd);

                    switch (result.status()) {

                        case REFRESHED ->
                                run.recordRefreshed(result);

                        case NOT_COVERED, WAITING_FOR_HOURLY ->
                                logger.debug(
                                        "Daily refresh skipped "
                                                + "sensorId={} localDate={} reason={}",
                                        sensor.getId(),
                                        localDate,
                                        result.status());

                        case ADVANCED, UP_TO_DATE ->
                                throw new IllegalStateException("Unexpected daily refresh result " + result.status());
                    }

                } catch (RuntimeException exception) {
                    run.recordRefreshFailure(sensor.getId());

                    logger.error(
                            "Daily rollup refresh failed "
                                    + "sensorId={} localDate={}",
                            sensor.getId(),
                            localDate,
                            exception);
                }
            }
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

        if (run.advancedBuckets == 0 && run.refreshedBuckets == 0) {
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
        private int refreshedBuckets;
        private long sourceRowsSummarized;
        private long hourlySummaryRowsConsumed;
        private long rawBoundaryRowsSummarized;

        private boolean madeProgress;
        private boolean bounded;

        private Duration maximumRollupLag = Duration.ZERO;

        private final Set<SensorDay> advancedThisRun = new HashSet<>();
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

            advancedThisRun.add(new SensorDay(sensorId, result.localDate()));
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




        private boolean isRefreshCandidate(
                Long sensorId,
                LocalDate localDate,
                Instant bucketStart,
                Instant bucketEnd,
                Instant refreshThreshold,
                Instant hourlyEligibleCoveredUntil,
                Instant eligibleBucketEnd
        ) {

            if (!caughtUpSensors.contains(sensorId)
                    || waitingSensors.contains(sensorId)
                    || failedSensors.contains(sensorId)) {
                return false;
            }

            DailyRollupBucketResult progress = latestProgress.get(sensorId);

            return progress != null
                    && progress.coverageStartedAt() != null
                    && progress.coveredUntil() != null
                    && !bucketStart.isBefore(
                    progress.coverageStartedAt())
                    && !bucketEnd.isAfter(
                    progress.coveredUntil())
                    && !bucketEnd.isAfter(eligibleBucketEnd)
                    && bucketEnd.isAfter(refreshThreshold)
                    && bucketStart.isBefore(
                    hourlyEligibleCoveredUntil)
                    && !advancedThisRun.contains(
                    new SensorDay(sensorId, localDate));
        }



        private void recordRefreshAttempt() {
            remainingBudget--;
        }



        private void recordRefreshed(DailyRollupBucketResult result) {
            refreshedBuckets++;
            sourceRowsSummarized += result.sourceSampleCount();
            hourlySummaryRowsConsumed += result.hourlySummaryRows();
            rawBoundaryRowsSummarized += result.rawBoundarySampleCount();
        }



        private void recordRefreshFailure(Long sensorId) {
            failedSensors.add(sensorId);
        }




        private record SensorDay(Long sensorId, LocalDate localDate) {
        }


    }

}