package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;




@Service
public class HourlySensorRollupBucketProcessor {

    @PersistenceContext
    private EntityManager entityManager;

    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;


    public HourlySensorRollupBucketProcessor( SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, SensorRollupCheckpointRepository checkpointRepository) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.checkpointRepository = checkpointRepository;
    }







    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HourlyRollupBucketResult advanceNextClosedHour(RollupSensorProjection sensor, Instant eligibleCoveredUntil) {

        RollupSensorProjection requiredSensor = Objects.requireNonNull(sensor,"sensor must not be null");
        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil, "eligibleCoveredUntil");
        Long sensorId = requiredSensor.getId();


        SensorRollupCheckpoint checkpoint = loadOrInitializeCheckpoint(sensorId);


        if (!checkpoint.getCoveredUntil().isBefore(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensorId, checkpoint);
        }


        Instant bucketStart = checkpoint.getCoveredUntil();
        Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);

        if (bucketEnd.isAfter(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensorId, checkpoint);
        }

        Instant attemptedAt = notBefore(Instant.now(), checkpoint.getUpdatedAt());

        attemptedAt = notBefore(attemptedAt,bucketEnd);
        checkpoint.recordAttempt(bucketStart,attemptedAt);

        RawSensorReadingAggregateProjection rawAggregate =
                sensorReadingRepository.aggregateForSummaryRange(
                        sensorId,
                        bucketStart,
                        bucketEnd);

        SensorSummaryAggregate aggregate = SensorSummaryAggregationSupport.fromRawReadings(requiredSensor.getType(), rawAggregate);


        Instant completedAt = notBefore(Instant.now(),attemptedAt);


        completedAt = saveOrRefreshHourlySummary(
                sensorId,
                bucketStart,
                aggregate,
                completedAt);

        checkpoint.advanceContiguously(bucketStart, bucketEnd,completedAt);

        checkpointRepository.saveAndFlush(checkpoint);

        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.ADVANCED,
                sensorId,
                bucketStart,
                bucketEnd,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                aggregate.getSourceSampleCount());
    }






    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HourlyRollupBucketResult refreshCoveredHour(RollupSensorProjection sensor, Instant bucketStart, Instant eligibleCoveredUntil) {

        RollupSensorProjection requiredSensor = Objects.requireNonNull(sensor, "sensor must not be null");
        Long sensorId = requiredSensor.getId();


        Instant requiredBucketStart = requireUtcHourBoundary(bucketStart, "bucketStart");
        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil, "eligibleCoveredUntil");
        Instant bucketEnd = requiredBucketStart.plus(1,ChronoUnit.HOURS);

        Optional<SensorRollupCheckpoint> checkpointCandidate =
                checkpointRepository.findBySensorIdAndStageForUpdate(
                        sensorId,
                        RollupStage.RAW_TO_HOURLY);

        if (checkpointCandidate.isEmpty()) {
            return new HourlyRollupBucketResult(
                    HourlyRollupBucketResult.Status.NOT_COVERED,
                    sensorId,
                    requiredBucketStart,
                    bucketEnd,
                    null,
                    null,
                    0);
        }

        SensorRollupCheckpoint checkpoint = checkpointCandidate.get();

        boolean covered = !requiredBucketStart.isBefore(
                checkpoint.getCoverageStartedAt())
                && !bucketEnd.isAfter(checkpoint.getCoveredUntil())
                && !bucketEnd.isAfter(requiredEligibleCoveredUntil);

        if (!covered) {
            return new HourlyRollupBucketResult(
                    HourlyRollupBucketResult.Status.NOT_COVERED,
                    sensorId,
                    requiredBucketStart,
                    bucketEnd,
                    checkpoint.getCoverageStartedAt(),
                    checkpoint.getCoveredUntil(),
                    0);
        }


        HourlySensorSummary summary = hourlySensorSummaryRepository
                .findBySensorIdAndBucketStart(sensorId, requiredBucketStart)
                .orElseThrow(() -> new IllegalStateException("Checkpoint covers an hourly bucket with no summary row"));


        RawSensorReadingAggregateProjection rawAggregate =
                sensorReadingRepository.aggregateForSummaryRange(
                        sensorId,
                        requiredBucketStart,
                        bucketEnd);

        SensorSummaryAggregate aggregate =
                SensorSummaryAggregationSupport.fromRawReadings(requiredSensor.getType(), rawAggregate);


        Instant refreshedAt = notBefore(Instant.now(),bucketEnd);
        refreshedAt = notBefore(refreshedAt,checkpoint.getUpdatedAt());
        refreshedAt = notBefore(refreshedAt,summary.getRefreshedAt());

        summary.refresh(aggregate,refreshedAt);
        hourlySensorSummaryRepository.saveAndFlush(summary);


        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.REFRESHED,
                sensorId,
                requiredBucketStart,
                bucketEnd,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                aggregate.getSourceSampleCount());
    }







    private static HourlyRollupBucketResult upToDateResult(Long sensorId, SensorRollupCheckpoint checkpoint) {
        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.UP_TO_DATE,
                sensorId,
                null,
                null,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                0);
    }







    private SensorRollupCheckpoint loadOrInitializeCheckpoint(Long sensorId) {

        return checkpointRepository.findBySensorIdAndStageForUpdate(sensorId, RollupStage.RAW_TO_HOURLY)
                .orElseGet(() -> initializeCheckpoint(sensorId));
    }







    private SensorRollupCheckpoint initializeCheckpoint(Long sensorId) {

        Instant earliestRecordedAt = sensorReadingRepository
                .findEarliestRecordedAt(sensorId)
                .orElseThrow(() -> new IllegalStateException("Sensor has recorded readings but no raw reading exists"));

        Instant coverageStartedAt = earliestRecordedAt.truncatedTo(ChronoUnit.HOURS);

        Instant initializedAt = notBefore(Instant.now(), coverageStartedAt);

        Sensor sensor = entityManager.getReference(Sensor.class, sensorId);

        SensorRollupCheckpoint checkpoint =
                SensorRollupCheckpoint.initialize(
                        sensor,
                        RollupStage.RAW_TO_HOURLY,
                        coverageStartedAt,
                        initializedAt);

        return checkpointRepository.saveAndFlush(checkpoint);
    }





    private Instant saveOrRefreshHourlySummary(Long sensorId, Instant bucketStart, SensorSummaryAggregate aggregate, Instant completedAt) {

        Optional<HourlySensorSummary> existingSummary = hourlySensorSummaryRepository.findBySensorIdAndBucketStart(sensorId, bucketStart);

        HourlySensorSummary summary;
        Instant effectiveCompletedAt = completedAt;

        if (existingSummary.isPresent()) {
            summary = existingSummary.get();

            effectiveCompletedAt = notBefore(effectiveCompletedAt, summary.getRefreshedAt());

            summary.refresh(aggregate,effectiveCompletedAt);
        } else {
            Sensor sensor = entityManager.getReference(Sensor.class, sensorId);
            summary = HourlySensorSummary.create(sensor, bucketStart, aggregate, effectiveCompletedAt);
        }

        hourlySensorSummaryRepository.saveAndFlush(summary);


        return effectiveCompletedAt;
    }






    private static Instant requireUtcHourBoundary(Instant value, String fieldName) {

        Instant requiredValue = Objects.requireNonNull(value, fieldName + " must not be null");

        if (!requiredValue.equals(requiredValue.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException(fieldName + " must be aligned to a UTC hour");
        }

        return requiredValue;
    }



    private static Instant notBefore(Instant candidate, Instant boundary) {
        return candidate.isBefore(boundary)
                ? boundary
                : candidate;
    }



}