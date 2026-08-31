package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.HourlyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.HourlySensorRollupService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;





@Component
public class HourlySensorRollupScheduler {

    private final Logger logger = LoggerFactory.getLogger(HourlySensorRollupScheduler.class);

    private final HourlySensorRollupService hourlySensorRollupService;
    private final SensorDataLifecyclePolicy lifecyclePolicy;

    public HourlySensorRollupScheduler(HourlySensorRollupService hourlySensorRollupService, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.hourlySensorRollupService = hourlySensorRollupService;
        this.lifecyclePolicy = lifecyclePolicy;
    }



    @Scheduled(fixedDelayString = "${app.sensor-data.lifecycle.hourly-rollup-interval:PT5M}")
    public void rollUpClosedUtcHours() {

        Instant startedAt = Instant.now();

        Instant eligibleCoveredUntil = startedAt
                .minus(lifecyclePolicy.getHourlyRollupGrace())
                .truncatedTo(ChronoUnit.HOURS);

        logger.info(
                "Hourly sensor rollup started eligibleCoveredUntil={} maximumBucketsPerRun={}",
                eligibleCoveredUntil,
                lifecyclePolicy.getMaximumBucketsPerRun());

        try {

            HourlyRollupRunResult result = hourlySensorRollupService.rollUpClosedHours(eligibleCoveredUntil);

            Instant completedAt = Instant.now();

            Duration duration = nonNegativeDuration(startedAt, completedAt);

            Duration rollupLag = calculateRollupLag(result.oldestCoveredUntil(), eligibleCoveredUntil);

            logger.info(
                    "Hourly sensor rollup finished status={} sensors={} attemptedBuckets={} advancedBuckets={} refreshedBuckets={} sourceRowsSummarized={} failedSensors={} bounded={} eligibleCoveredUntil={} oldestCoveredUntil={} rollupLag={} duration={}",
                    result.status(),
                    result.sensorCount(),
                    result.attemptedBuckets(),
                    result.advancedBuckets(),
                    result.refreshedBuckets(),
                    result.sourceRowsSummarized(),
                    result.failedSensors(),
                    result.bounded(),
                    eligibleCoveredUntil,
                    result.oldestCoveredUntil(),
                    rollupLag,
                    duration);
        } catch (RuntimeException exception) {
            Instant failedAt = Instant.now();

            logger.error(
                    "Hourly sensor rollup failed eligibleCoveredUntil={} duration={}",
                    eligibleCoveredUntil,
                    nonNegativeDuration(startedAt,failedAt),
                    exception);
        }
    }



    private static Duration calculateRollupLag(Instant oldestCoveredUntil, Instant eligibleCoveredUntil) {

        if (oldestCoveredUntil == null || !oldestCoveredUntil.isBefore(eligibleCoveredUntil)) {

            return Duration.ZERO;
        }

        return Duration.between(oldestCoveredUntil, eligibleCoveredUntil);
    }



    private static Duration nonNegativeDuration(Instant start, Instant end) {

        if (end.isBefore(start)) {
            return Duration.ZERO;
        }

        return Duration.between(start,end);
    }
}