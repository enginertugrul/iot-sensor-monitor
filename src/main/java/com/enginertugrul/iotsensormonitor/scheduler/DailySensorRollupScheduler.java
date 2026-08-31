package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.DailyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.DailySensorRollupService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;


@Component
public class DailySensorRollupScheduler {

    private final Logger logger = LoggerFactory.getLogger(DailySensorRollupScheduler.class);

    private final DailySensorRollupService dailySensorRollupService;
    private final SensorDataLifecyclePolicy lifecyclePolicy;

    public DailySensorRollupScheduler(DailySensorRollupService dailySensorRollupService, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.dailySensorRollupService = dailySensorRollupService;
        this.lifecyclePolicy = lifecyclePolicy;
    }



    @Scheduled(fixedDelayString = "${app.sensor-data.lifecycle.daily-rollup-interval:PT15M}")
    public void rollUpClosedLocalDays() {

        Instant startedAt = Instant.now();

        Instant eligibleBucketEnd = startedAt.minus(lifecyclePolicy.getDailyRollupGrace());

        logger.info(
                "Daily sensor rollup started eligibleBucketEnd={} maximumBucketsPerRun={}",
                eligibleBucketEnd,
                lifecyclePolicy.getMaximumBucketsPerRun());

        try {

            DailyRollupRunResult result = dailySensorRollupService.rollUpClosedLocalDays(eligibleBucketEnd);

            Instant completedAt = Instant.now();

            logger.info(
                    "Daily sensor rollup finished status={} sensors={} attemptedBuckets={} advancedBuckets={} refreshedBuckets={} sourceRowsSummarized={} hourlySummaryRowsConsumed={} rawBoundaryRowsSummarized={} waitingSensors={} failedSensors={} bounded={} eligibleBucketEnd={} oldestCoveredUntil={} maximumRollupLag={} duration={}",
                    result.status(),
                    result.sensorCount(),
                    result.attemptedBuckets(),
                    result.advancedBuckets(),
                    result.refreshedBuckets(),
                    result.sourceRowsSummarized(),
                    result.hourlySummaryRowsConsumed(),
                    result.rawBoundaryRowsSummarized(),
                    result.waitingSensors(),
                    result.failedSensors(),
                    result.bounded(),
                    eligibleBucketEnd,
                    result.oldestCoveredUntil(),
                    result.maximumRollupLag(),
                    nonNegativeDuration(startedAt,completedAt));
        } catch (RuntimeException exception) {
            Instant failedAt = Instant.now();

            logger.error(
                    "Daily sensor rollup failed eligibleBucketEnd={} duration={}",
                    eligibleBucketEnd,
                    nonNegativeDuration(startedAt,failedAt),
                    exception);
        }
    }



    private static Duration nonNegativeDuration(Instant start, Instant end) {

        if (end.isBefore(start)) {
            return Duration.ZERO;
        }

        return Duration.between(start,end);
    }

}