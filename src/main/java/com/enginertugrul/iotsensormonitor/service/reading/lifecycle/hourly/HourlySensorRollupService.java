package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupCandidateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
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
        List<RollupCandidateProjection> sensors = sensorRepository.findSensorsForRollup();
        RollupRunState run = new RollupRunState(lifecyclePolicy.getMaximumBucketsPerRun());


        seedPendingHours(sensors,eligibleCoveredUntil,run);

        catchUpClosedHours(sensors, requiredEligibleCoveredUntil, run);


        run.bounded = run.isBudgetExhausted() && run.hasUnfinishedCatchUp(sensors);


        Instant oldestCoveredUntil = checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY).orElse(null);


        return new HourlyRollupRunResult(
                determineRunStatus(run),
                sensors.size(),
                run.maximumBuckets - run.remainingBudget,
                run.advancedBuckets,
                run.sourceRowsSummarized,
                run.failedSensors.size(),
                run.bounded,
                oldestCoveredUntil);
    }





    private void seedPendingHours(List<RollupCandidateProjection> sensors, Instant eligibleCoveredUntil,  RollupRunState run) {

        for (RollupCandidateProjection sensor : sensors) {
            Instant nextBucketStart = sensor.getHourlyCoveredUntil();
            if (nextBucketStart == null) {
                nextBucketStart = sensor.getFirstReadingAt().truncatedTo(ChronoUnit.HOURS);
            }
            if (!nextBucketStart.isBefore(eligibleCoveredUntil)) {
                run.caughtUpSensors.add(sensor.getId());
            }
        }

    }





    private void catchUpClosedHours(List<RollupCandidateProjection> sensors, Instant requiredEligibleCoveredUntil, RollupRunState run) {


        do {

            run.madeProgress = false;

            for (RollupCandidateProjection sensor : sensors) {

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







    private void attemptNextClosedHour(RollupCandidateProjection sensor, Instant requiredEligibleCoveredUntil, RollupRunState run) {

        Long sensorId = sensor.getId();

        try {

            HourlyRollupBucketResult result = bucketProcessor.advanceNextClosedHour(sensor, requiredEligibleCoveredUntil);


            switch (result.status()) {

                case ADVANCED ->
                        run.recordAdvanced(sensorId, result, requiredEligibleCoveredUntil);

                case UP_TO_DATE ->
                        run.recordUpToDate(sensorId,result);

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










    private static HourlyRollupRunResult.Status determineRunStatus(RollupRunState run) {

        if (!run.failedSensors.isEmpty()) {
            return HourlyRollupRunResult.Status.PARTIAL_FAILURE;
        }

        if (run.bounded) {
            return HourlyRollupRunResult.Status.BOUNDED;
        }

        if (run.advancedBuckets == 0) {
            return HourlyRollupRunResult.Status.NO_WORK;
        }

        return HourlyRollupRunResult.Status.SUCCEEDED;
    }



    private static Instant requireUtcHourBoundary(Instant value) {

        Objects.requireNonNull(value, "eligibleCoveredUntil must not be null");

        if (!value.equals(value.truncatedTo(ChronoUnit.HOURS))) {

            throw new IllegalArgumentException("eligibleCoveredUntil must be aligned to a UTC hour");
        }

        return value;
    }





    private static final class RollupRunState {

        private final int maximumBuckets;
        private int remainingBudget;

        private int advancedBuckets;
        private long sourceRowsSummarized;

        private boolean madeProgress;
        private boolean bounded;

        private final Set<Long> caughtUpSensors = new HashSet<>();
        private final Set<Long> failedSensors = new HashSet<>();

        private final Map<Long,HourlyRollupBucketResult> latestProgress = new HashMap<>();



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



        private boolean hasUnfinishedCatchUp(List<RollupCandidateProjection> sensors) {

            for (RollupCandidateProjection sensor : sensors) {

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



    }




}





