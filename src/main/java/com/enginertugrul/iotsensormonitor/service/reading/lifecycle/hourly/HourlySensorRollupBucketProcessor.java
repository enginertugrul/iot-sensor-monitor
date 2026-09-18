package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.*;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;




@Service
public class HourlySensorRollupBucketProcessor {


    private final SensorRepository sensorRepository;
    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;
    private final Clock clock;


    public HourlySensorRollupBucketProcessor(SensorRepository sensorRepository, SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, SensorRollupCheckpointRepository checkpointRepository, Clock clock) {
        this.sensorRepository = sensorRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.checkpointRepository = checkpointRepository;
        this.clock = clock;
    }







    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HourlyRollupBucketResult advanceNextClosedHour(RollupCandidateProjection sensorSnapshot, Instant eligibleCoveredUntil) {

        Sensor sensor = lockSensor(sensorSnapshot);
        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil,"eligibleCoveredUntil");
        Long sensorId = sensor.getId();
        SensorRollupCheckpoint checkpoint = loadOrInitializeCheckpoint(sensor);

        if (!checkpoint.getCoveredUntil().isBefore(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensorId,checkpoint);
        }

        Instant bucketStart = checkpoint.getCoveredUntil();
        Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);
        if (bucketEnd.isAfter(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensorId,checkpoint);
        }

        Instant attemptedAt = notBefore(clock.instant(),checkpoint.getUpdatedAt());
        attemptedAt = notBefore(attemptedAt,bucketEnd);
        checkpoint.recordAttempt(bucketStart,attemptedAt);

        RawSensorReadingAggregateProjection rawAggregate = sensorReadingRepository.aggregateForSummaryRange(sensorId,bucketStart,bucketEnd);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(sensor.getType(),rawAggregate);

        Instant completedAt = notBefore(clock.instant(),attemptedAt);
        completedAt = upsertHourlySummaryDuringAdvance(sensorId, bucketStart, aggregate, completedAt);

        checkpoint.advanceContiguously(bucketStart,bucketEnd,completedAt);
        checkpointRepository.saveAndFlush(checkpoint);

        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.ADVANCED, sensorId, bucketStart, bucketEnd,
                checkpoint.getCoverageStartedAt(), checkpoint.getCoveredUntil(), aggregate.getSourceSampleCount());
    }





    private Sensor lockSensor(RollupCandidateProjection sensorSnapshot) {
        Objects.requireNonNull(sensorSnapshot,"sensor must not be null");
        return sensorRepository.findByIdForUpdate(sensorSnapshot.getId()).orElseThrow(SensorNotFoundException::new);
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







    private SensorRollupCheckpoint loadOrInitializeCheckpoint(Sensor sensor) {

        return checkpointRepository.findBySensorIdAndStageForUpdate(sensor.getId(), RollupStage.RAW_TO_HOURLY)
                .orElseGet(() -> initializeCheckpoint(sensor));
    }







    private SensorRollupCheckpoint initializeCheckpoint(Sensor sensor) {

        Instant firstReadingAt = sensor.getFirstReadingAt();

        Instant coverageStartedAt = firstReadingAt.truncatedTo(ChronoUnit.HOURS);

        Instant initializedAt = notBefore(clock.instant(), coverageStartedAt);

        Sensor sensorReference = sensorRepository.getReferenceById(sensor.getId());

        SensorRollupCheckpoint checkpoint =
                SensorRollupCheckpoint.initialize(
                        sensorReference,
                        RollupStage.RAW_TO_HOURLY,
                        coverageStartedAt,
                        initializedAt);

        return checkpointRepository.saveAndFlush(checkpoint);
    }





    private Instant upsertHourlySummaryDuringAdvance(Long sensorId, Instant bucketStart, SensorSummaryAggregate aggregate, Instant completedAt) {

        Optional<HourlySensorSummary> existingSummary = hourlySensorSummaryRepository.findBySensorIdAndBucketStart(sensorId, bucketStart);

        HourlySensorSummary summary;
        Instant effectiveCompletedAt = completedAt;

        if (existingSummary.isPresent()) {
            summary = existingSummary.get();

            effectiveCompletedAt = notBefore(effectiveCompletedAt, summary.getRefreshedAt());

            summary.refresh(aggregate,effectiveCompletedAt);
        } else {
            Sensor sensor = sensorRepository.getReferenceById(sensorId);
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