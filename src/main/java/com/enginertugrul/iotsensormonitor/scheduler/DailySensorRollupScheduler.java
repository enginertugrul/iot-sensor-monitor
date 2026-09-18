package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailySensorRollupService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;


@Component
public class DailySensorRollupScheduler {

    private final Logger logger = LoggerFactory.getLogger(DailySensorRollupScheduler.class);

    private final DailySensorRollupService dailySensorRollupService;
    private final SensorDataLifecyclePolicy lifecyclePolicy;
    private final Clock clock;

    public DailySensorRollupScheduler(DailySensorRollupService dailySensorRollupService, SensorDataLifecyclePolicy lifecyclePolicy, Clock clock) {
        this.dailySensorRollupService = dailySensorRollupService;
        this.lifecyclePolicy = lifecyclePolicy;
        this.clock = clock;
    }



    @Scheduled(fixedDelayString = "${app.sensor-data.lifecycle.daily-rollup-interval:PT15M}")
    public void rollUpClosedLocalDays() {

        Instant startedAt = clock.instant();

        Instant eligibleBucketEnd = startedAt.minus(lifecyclePolicy.getDailyRollupGrace());

        logger.info(
                "Daily sensor rollup started eligibleBucketEnd={} maximumBucketsPerRun={}",
                eligibleBucketEnd,
                lifecyclePolicy.getMaximumBucketsPerRun());

        try {

            DailyRollupRunResult result = dailySensorRollupService.rollUpClosedLocalDays(eligibleBucketEnd);

            Instant completedAt = clock.instant();

            logger.info(
                    "Daily sensor rollup finished status={} sensors={} attemptedBuckets={} advancedBuckets={} sourceRowsSummarized={} hourlySummaryRowsConsumed={} rawBoundaryRowsSummarized={} waitingSensors={} failedSensors={} bounded={} eligibleBucketEnd={} oldestCoveredUntil={} maximumRollupLag={} duration={}",
                    result.status(),
                    result.sensorCount(),
                    result.attemptedBuckets(),
                    result.advancedBuckets(),
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
            Instant failedAt = clock.instant();

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