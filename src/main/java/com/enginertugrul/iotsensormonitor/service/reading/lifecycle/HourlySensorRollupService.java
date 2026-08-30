package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupSensorProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;





@Service
public class HourlySensorRollupService {


    private final Logger logger = LoggerFactory.getLogger(HourlySensorRollupService.class);


    private final SensorRepository sensorRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;
    private final HourlySensorRollupBucketProcessor bucketProcessor;
    private final SensorDataLifecyclePolicy lifecyclePolicy;



    public HourlySensorRollupService(SensorRepository sensorRepository, SensorRollupCheckpointRepository checkpointRepository, HourlySensorRollupBucketProcessor bucketProcessor, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.sensorRepository = sensorRepository;
        this.checkpointRepository = checkpointRepository;
        this.bucketProcessor = bucketProcessor;
        this.lifecyclePolicy = lifecyclePolicy;
    }






    public HourlyRollupRunResult rollUpClosedHours(Instant eligibleCoveredUntil) {

        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil);
        List<RollupSensorProjection> sensors = sensorRepository.findSensorsForRollup();
        RollupRunState run = new RollupRunState(lifecyclePolicy.getMaximumBucketsPerRun());


        catchUpClosedHours(sensors, requiredEligibleCoveredUntil, run);


        run.bounded = run.isBudgetExhausted() && run.hasUnfinishedCatchUp(sensors);

        refreshTrailingCoveredHours(sensors, requiredEligibleCoveredUntil, run);

        Instant oldestCoveredUntil = checkpointRepository.findOldestCoveredUntilByStage(
                        RollupStage.RAW_TO_HOURLY)
                .orElse(null);

        HourlyRollupRunResult.Status status = determineRunStatus(run);

        return new HourlyRollupRunResult(
                status,
                sensors.size(),
                run.maximumBuckets - run.remainingBudget,
                run.advancedBuckets,
                run.refreshedBuckets,
                run.sourceRowsSummarized,
                run.failedSensors.size(),
                run.bounded,
                oldestCoveredUntil);
    }








    private void catchUpClosedHours(List<RollupSensorProjection> sensors, Instant requiredEligibleCoveredUntil, RollupRunState run) {


        do {

            run.madeProgress = false;

            for (RollupSensorProjection sensor : sensors) {

                if (run.isBudgetExhausted()) {
                    break;
                }

                if (!run.canAttemptCatchUp(sensor.getId())) {
                    continue;
                }

                attemptNextClosedHour(sensor, requiredEligibleCoveredUntil, run);
            }

        } while (run.madeProgress && run.hasRemainingBudget());

    }







    private void attemptNextClosedHour(RollupSensorProjection sensor, Instant requiredEligibleCoveredUntil, RollupRunState run) {

        Long sensorId = sensor.getId();

        try {

            HourlyRollupBucketResult result = bucketProcessor.advanceNextClosedHour(sensor, requiredEligibleCoveredUntil);


            switch (result.status()) {

                case ADVANCED ->
                        run.recordAdvanced(sensorId, result, requiredEligibleCoveredUntil);

                case UP_TO_DATE ->
                        run.recordUpToDate(sensorId,result);

                case REFRESHED, NOT_COVERED ->
                        throw new IllegalStateException("Unexpected hourly advance result " + result.status());
            }

        } catch (RuntimeException exception) {

            run.recordAdvanceFailure(sensorId);

            logger.error(
                    "Hourly rollup advance failed sensorId={} expectedBucketStart={} eligibleCoveredUntil={}",
                    sensorId,
                    run.expectedBucketStart(sensor.getId()),
                    requiredEligibleCoveredUntil,
                    exception);
        }
    }







    private void refreshTrailingCoveredHours(List<RollupSensorProjection> sensors, Instant requiredEligibleCoveredUntil, RollupRunState run) {

        Instant refreshThreshold = requiredEligibleCoveredUntil.minus(lifecyclePolicy.getHourlyRollupTrailingWindow() );


        for (
                Instant bucketStart = requiredEligibleCoveredUntil.minus(1, ChronoUnit.HOURS);

                bucketStart.plus(1, ChronoUnit.HOURS).isAfter(refreshThreshold);

                bucketStart = bucketStart.minus(1, ChronoUnit.HOURS)

        ) {
            Instant bucketEnd = bucketStart.plus(1, ChronoUnit.HOURS);

            for (RollupSensorProjection sensor : sensors) {

                if (!run.isRefreshCandidate(sensor.getId(), bucketStart, bucketEnd)) {

                    continue;
                }


                if (run.isBudgetExhausted()) {
                    run.bounded = true;
                    return;
                }


                run.recordRefreshAttempt();

                refreshOneCoveredHour(
                        sensor,
                        bucketStart,
                        bucketEnd,
                        requiredEligibleCoveredUntil,
                        run);

            }

        }

    }






    private void refreshOneCoveredHour(
            RollupSensorProjection sensor,
            Instant bucketStart,
            Instant bucketEnd,
            Instant requiredEligibleCoveredUntil,
            RollupRunState run
    ) {

        Long sensorId = sensor.getId();

        try {

            HourlyRollupBucketResult result = bucketProcessor.refreshCoveredHour(sensor, bucketStart, requiredEligibleCoveredUntil);

            switch (result.status()) {

                case REFRESHED ->
                        run.recordRefreshed(result);


                case NOT_COVERED ->
                        logger.debug(
                                "Hourly summary refresh skipped sensorId={} bucketStart={} reason={}",
                                sensorId,
                                bucketStart,
                                result.status());

                case ADVANCED, UP_TO_DATE -> throw new IllegalStateException("Unexpected hourly refresh result " + result.status());
            }

        } catch (RuntimeException exception) {

            run.recordRefreshFailure(sensorId);

            logger.error(
                    "Hourly rollup refresh failed sensorId={} bucketStart={} bucketEnd={}",
                    sensorId,
                    bucketStart,
                    bucketEnd,
                    exception);
        }
    }




    private static HourlyRollupRunResult.Status determineRunStatus(RollupRunState run) {

        if (!run.failedSensors.isEmpty()) {
            return HourlyRollupRunResult.Status.PARTIAL_FAILURE;
        }

        if (run.bounded) {
            return HourlyRollupRunResult.Status.BOUNDED;
        }

        if (run.advancedBuckets == 0 && run.refreshedBuckets == 0) {

            return HourlyRollupRunResult.Status.NO_WORK;
        }

        return HourlyRollupRunResult.Status.SUCCEEDED;
    }



    private static Instant requireUtcHourBoundary(Instant value) {

        Instant requiredValue = Objects.requireNonNull(value, "eligibleCoveredUntil must not be null");

        if (!requiredValue.equals(requiredValue.truncatedTo(ChronoUnit.HOURS))) {

            throw new IllegalArgumentException("eligibleCoveredUntil must be aligned to a UTC hour");
        }

        return requiredValue;
    }





    private static final class RollupRunState {

        private final int maximumBuckets;
        private int remainingBudget;

        private int advancedBuckets;
        private int refreshedBuckets;
        private long sourceRowsSummarized;

        private boolean madeProgress;
        private boolean bounded;

        private final Set<Long> caughtUpSensors = new HashSet<>();
        private final Set<Long> failedSensors = new HashSet<>();

        private final Map<Long,HourlyRollupBucketResult> latestProgress = new HashMap<>();

        private final Set<SensorHour> advancedThisRun = new HashSet<>();


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



        private void recordAdvanced(Long sensorId, HourlyRollupBucketResult result, Instant requiredEligibleCoveredUntil) {

            remainingBudget--;
            advancedBuckets++;
            sourceRowsSummarized += result.sourceSampleCount();

            latestProgress.put(sensorId,result);

            advancedThisRun.add(new SensorHour(sensorId, result.bucketStart()));

            madeProgress = true;

            if (!result.coveredUntil().isBefore(requiredEligibleCoveredUntil)) {

                caughtUpSensors.add(sensorId);
            }
        }



        private void recordUpToDate(Long sensorId, HourlyRollupBucketResult result) {

            latestProgress.put(sensorId,result);
            caughtUpSensors.add(sensorId);
        }






        private void recordAdvanceFailure(Long sensorId) {
            remainingBudget--;
            failedSensors.add(sensorId);
        }



        private Instant expectedBucketStart(Long sensorId) {

            HourlyRollupBucketResult previousProgress = latestProgress.get(sensorId);

            return previousProgress == null
                    ? null
                    : previousProgress.coveredUntil();
        }



        private boolean isRefreshCandidate(Long sensorId, Instant bucketStart, Instant bucketEnd) {

            if (!caughtUpSensors.contains(sensorId)
                    || failedSensors.contains(sensorId)) {

                return false;
            }

            HourlyRollupBucketResult progress = latestProgress.get(sensorId);

            return progress != null
                    && progress.coverageStartedAt() != null
                    && !bucketStart.isBefore(
                    progress.coverageStartedAt())
                    && !bucketEnd.isAfter(
                    progress.coveredUntil())
                    && !advancedThisRun.contains(
                    new SensorHour(sensorId, bucketStart));
        }



        private void recordRefreshAttempt() {
            remainingBudget--;
        }



        private void recordRefreshed(HourlyRollupBucketResult result) {

            refreshedBuckets++;
            sourceRowsSummarized += result.sourceSampleCount();
        }



        private void recordRefreshFailure(Long sensorId) {
            failedSensors.add(sensorId);
        }
    }



    private record SensorHour(Long sensorId, Instant bucketStart) {
    }



}





