package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataPurgeTierResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataRetentionRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataRetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;




@Component
public class SensorDataRetentionScheduler {

    private final Logger logger = LoggerFactory.getLogger(SensorDataRetentionScheduler.class);

    private final SensorDataRetentionService retentionService;
    private final SensorDataLifecyclePolicy lifecyclePolicy;
    private final Clock clock;

    public SensorDataRetentionScheduler(SensorDataRetentionService retentionService, SensorDataLifecyclePolicy lifecyclePolicy, Clock clock) {
        this.retentionService = retentionService;
        this.lifecyclePolicy = lifecyclePolicy;
        this.clock = clock;
    }



    @Scheduled(fixedDelayString = "${app.sensor-data.lifecycle.purge-interval:PT1H}")
    public void purgeExpiredSensorData() {

        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();

        logger.info(
                "Sensor data retention started runId={} currentTime={} deleteBatchSize={} maximumDeleteBatchesPerTier={} rawRetention={} hourlyRetention={} dailyRetention={}",
                runId,
                startedAt,
                lifecyclePolicy.getDeleteBatchSize(),
                lifecyclePolicy.getMaximumDeleteBatchesPerTierPerRun(),
                lifecyclePolicy.getRawRetention(),
                lifecyclePolicy.getHourlyRetention(),
                lifecyclePolicy.getDailyRetention());

        try {
            SensorDataRetentionRunResult result =
                    retentionService.purgeExpiredData(startedAt);

            logTierResult(runId,result.rawReadings());
            logTierResult(runId,result.hourlySummaries());
            logTierResult(runId,result.dailySummaries());

            Instant completedAt = clock.instant();

            logger.info(
                    "Sensor data retention finished runId={} status={} sensors={} operationsAttempted={} deleteBatchesAttempted={} deletionBatches={} rowsDeleted={} oldestExistingRawToHourlyCoveredUntil={} oldestExistingHourlyToDailyCoveredUntil={} duration={}",
                    runId,
                    result.status(),
                    result.sensorCount(),
                    result.totalOperationsAttempted(),
                    result.totalDeleteBatchesAttempted(),
                    result.totalDeletionBatches(),
                    result.totalRowsDeleted(),
                    result.oldestRawToHourlyCoveredUntil(),
                    result.oldestHourlyToDailyCoveredUntil(),
                    nonNegativeDuration(startedAt,completedAt));
        } catch (RuntimeException exception) {
            Instant failedAt = clock.instant();

            logger.error(
                    "Sensor data retention failed runId={} duration={} retry=NEXT_SCHEDULED_RUN",
                    runId,
                    nonNegativeDuration(startedAt,failedAt),
                    exception);
        }
    }



    private void logTierResult(UUID runId, SensorDataPurgeTierResult result) {
        logger.info(
                "Sensor data retention tier finished runId={} tier={} status={} retentionBoundary={} operationsAttempted={} deleteBatchesAttempted={} deletionBatches={} rowsDeleted={} noExpiredRowsSensors={} waitingForHourlyCoverageSensors={} waitingForDailyCoverageSensors={} concurrentlyDeferredSensors={} failedSensors={} bounded={}",
                runId,
                result.tier(),
                result.status(),
                result.retentionBoundary(),
                result.operationsAttempted(),
                result.deleteBatchesAttempted(),
                result.deletionBatches(),
                result.rowsDeleted(),
                result.noExpiredRowsSensors(),
                result.waitingForHourlyCoverageSensors(),
                result.waitingForDailyCoverageSensors(),
                result.concurrentlyDeferredSensors(),
                result.failedSensors(),
                result.bounded());
    }



    private static Duration nonNegativeDuration(Instant start, Instant end) {
        if (end.isBefore(start)) {
            return Duration.ZERO;
        }

        return Duration.between(start,end);
    }
}