package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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


    public HourlySensorRollupBucketProcessor(SensorRepository sensorRepository, SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, SensorRollupCheckpointRepository checkpointRepository) {
        this.sensorRepository = sensorRepository;
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.checkpointRepository = checkpointRepository;
    }







    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HourlyRollupBucketResult advanceNextClosedHour(Long sensorId, Instant eligibleCoveredUntil) {

        long requiredSensorId = Objects.requireNonNull(sensorId,"sensorId must not be null");

        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil, "eligibleCoveredUntil");

        Optional<Sensor> sensorCandidate = sensorRepository.findByIdForUpdate(requiredSensorId);

        if (sensorCandidate.isEmpty()) {
            return new HourlyRollupBucketResult(
                    HourlyRollupBucketResult.Status.SENSOR_NOT_FOUND,
                    requiredSensorId,
                    null,
                    null,
                    null,
                    null,
                    0);
        }

        Sensor sensor = sensorCandidate.get();
        SensorRollupCheckpoint checkpoint = loadOrInitializeCheckpoint(sensor);

        if (!checkpoint.getCoveredUntil().isBefore(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensor,checkpoint);
        }

        Instant bucketStart = checkpoint.getCoveredUntil();
        Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);

        if (bucketEnd.isAfter(requiredEligibleCoveredUntil)) {
            return upToDateResult(sensor,checkpoint);
        }

        Instant attemptedAt = notBefore(Instant.now(), checkpoint.getUpdatedAt());

        attemptedAt = notBefore(attemptedAt,bucketEnd);
        checkpoint.recordAttempt(bucketStart,attemptedAt);

        RawSensorReadingAggregateProjection rawAggregate =
                sensorReadingRepository.aggregateForHourlySummary(
                        sensor.getId(),
                        bucketStart,
                        bucketEnd);

        SensorSummaryAggregate aggregate = toSummaryAggregate(sensor,rawAggregate);

        Instant completedAt = notBefore(Instant.now(),attemptedAt);

        completedAt = saveOrRefreshHourlySummary(
                sensor,
                bucketStart,
                aggregate,
                completedAt);

        checkpoint.advanceContiguously(bucketStart, bucketEnd,completedAt);

        checkpointRepository.saveAndFlush(checkpoint);

        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.ADVANCED,
                sensor.getId(),
                bucketStart,
                bucketEnd,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                aggregate.getSourceSampleCount());
    }






    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HourlyRollupBucketResult refreshCoveredHour(Long sensorId, Instant bucketStart, Instant eligibleCoveredUntil) {

        long requiredSensorId = Objects.requireNonNull(sensorId, "sensorId must not be null");

        Instant requiredBucketStart = requireUtcHourBoundary(bucketStart, "bucketStart");

        Instant requiredEligibleCoveredUntil = requireUtcHourBoundary(eligibleCoveredUntil, "eligibleCoveredUntil");

        Instant bucketEnd = requiredBucketStart.plus(1,ChronoUnit.HOURS);

        Optional<Sensor> sensorCandidate = sensorRepository.findByIdForUpdate(requiredSensorId);

        if (sensorCandidate.isEmpty()) {
            return new HourlyRollupBucketResult(
                    HourlyRollupBucketResult.Status.SENSOR_NOT_FOUND,
                    requiredSensorId,
                    requiredBucketStart,
                    bucketEnd,
                    null,
                    null,
                    0);
        }

        Sensor sensor = sensorCandidate.get();

        Optional<SensorRollupCheckpoint> checkpointCandidate =
                checkpointRepository.findBySensorIdAndStageForUpdate(
                        sensor.getId(),
                        RollupStage.RAW_TO_HOURLY);

        if (checkpointCandidate.isEmpty()) {
            return new HourlyRollupBucketResult(
                    HourlyRollupBucketResult.Status.NOT_COVERED,
                    sensor.getId(),
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
                    sensor.getId(),
                    requiredBucketStart,
                    bucketEnd,
                    checkpoint.getCoverageStartedAt(),
                    checkpoint.getCoveredUntil(),
                    0);
        }

        HourlySensorSummary summary = hourlySensorSummaryRepository
                .findBySensorIdAndBucketStart(
                        sensor.getId(),
                        requiredBucketStart)
                .orElseThrow(() -> new IllegalStateException(
                        "Checkpoint covers an hourly bucket with no summary row"));

        RawSensorReadingAggregateProjection rawAggregate =
                sensorReadingRepository.aggregateForHourlySummary(
                        sensor.getId(),
                        requiredBucketStart,
                        bucketEnd);

        SensorSummaryAggregate aggregate = toSummaryAggregate(sensor,rawAggregate);

        Instant refreshedAt = notBefore(Instant.now(),bucketEnd);
        refreshedAt = notBefore(refreshedAt,checkpoint.getUpdatedAt());
        refreshedAt = notBefore(refreshedAt,summary.getRefreshedAt());

        summary.refresh(aggregate,refreshedAt);
        hourlySensorSummaryRepository.saveAndFlush(summary);

        verifyHourlySummary(
                sensor,
                summary,
                requiredBucketStart,
                aggregate,
                refreshedAt);

        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.REFRESHED,
                sensor.getId(),
                requiredBucketStart,
                bucketEnd,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                aggregate.getSourceSampleCount());
    }



    private static HourlyRollupBucketResult upToDateResult(Sensor sensor, SensorRollupCheckpoint checkpoint) {
        return new HourlyRollupBucketResult(
                HourlyRollupBucketResult.Status.UP_TO_DATE,
                sensor.getId(),
                null,
                null,
                checkpoint.getCoverageStartedAt(),
                checkpoint.getCoveredUntil(),
                0);
    }


    private SensorRollupCheckpoint loadOrInitializeCheckpoint(Sensor sensor) {

        return checkpointRepository.findBySensorIdAndStageForUpdate(
                        sensor.getId(),
                        RollupStage.RAW_TO_HOURLY)
                .orElseGet(() -> initializeCheckpoint(sensor));
    }





    private SensorRollupCheckpoint initializeCheckpoint(Sensor sensor) {

        Instant earliestRecordedAt = sensorReadingRepository
                .findEarliestRecordedAt(sensor.getId())
                .orElseThrow(() -> new IllegalStateException("Sensor has recorded readings but no raw reading exists"));

        Instant coverageStartedAt = earliestRecordedAt.truncatedTo(ChronoUnit.HOURS);

        Instant initializedAt = notBefore(Instant.now(), coverageStartedAt);

        SensorRollupCheckpoint checkpoint =
                SensorRollupCheckpoint.initialize(
                        sensor,
                        RollupStage.RAW_TO_HOURLY,
                        coverageStartedAt,
                        initializedAt);

        return checkpointRepository.saveAndFlush(checkpoint);
    }



    private Instant saveOrRefreshHourlySummary(Sensor sensor, Instant bucketStart, SensorSummaryAggregate aggregate, Instant completedAt) {

        Optional<HourlySensorSummary> existingSummary = hourlySensorSummaryRepository.findBySensorIdAndBucketStart(sensor.getId(), bucketStart);

        HourlySensorSummary summary;
        Instant effectiveCompletedAt = completedAt;

        if (existingSummary.isPresent()) {
            summary = existingSummary.get();

            effectiveCompletedAt = notBefore(effectiveCompletedAt, summary.getRefreshedAt());

            summary.refresh(aggregate,effectiveCompletedAt);
        } else {
            summary = HourlySensorSummary.create(sensor, bucketStart, aggregate, effectiveCompletedAt);
        }

        hourlySensorSummaryRepository.saveAndFlush(summary);

        verifyHourlySummary(
                sensor,
                summary,
                bucketStart,
                aggregate,
                effectiveCompletedAt);

        return effectiveCompletedAt;
    }



    private SensorSummaryAggregate toSummaryAggregate(Sensor sensor, RawSensorReadingAggregateProjection rawAggregate) {


        SensorType sensorType = sensor.getType();
        long sourceSampleCount = rawAggregate.getSourceSampleCount();

        if (sensorType == SensorType.MOTION) {
            boolean validMotionShape =
                    rawAggregate.getBooleanSampleCount()
                            == sourceSampleCount
                            && rawAggregate.getNumericSampleCount() == 0
                            && rawAggregate.getUnitSampleCount() == 0
                            && rawAggregate.getMinimumUnit() == null
                            && rawAggregate.getMaximumUnit() == null
                            && rawAggregate.getNumericSum() == null
                            && rawAggregate.getNumericMinimum() == null
                            && rawAggregate.getNumericMaximum() == null;

            if (!validMotionShape) {
                throw new IllegalStateException("Raw readings do not match sensor type " + sensorType);
            }

            return SensorSummaryAggregate.motion(sourceSampleCount, rawAggregate.getTrueSampleCount());
        }

        MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensorType);

        boolean validNumericShape =
                rawAggregate.getNumericSampleCount()
                        == sourceSampleCount
                        && rawAggregate.getBooleanSampleCount() == 0
                        && rawAggregate.getUnitSampleCount()
                        == sourceSampleCount
                        && rawAggregate.getTrueSampleCount() == 0;

        if (sourceSampleCount == 0) {
            validNumericShape = validNumericShape
                    && rawAggregate.getMinimumUnit() == null
                    && rawAggregate.getMaximumUnit() == null
                    && rawAggregate.getNumericSum() == null
                    && rawAggregate.getNumericMinimum() == null
                    && rawAggregate.getNumericMaximum() == null;

            if (!validNumericShape) {
                throw new IllegalStateException(
                        "Raw readings do not match sensor type "
                                + sensorType);
            }

            return SensorSummaryAggregate.emptyNumeric(sensorType);
        }

        validNumericShape = validNumericShape
                && canonicalUnit.name().equals(
                rawAggregate.getMinimumUnit())
                && canonicalUnit.name().equals(
                rawAggregate.getMaximumUnit());

        if (!validNumericShape) {
            throw new IllegalStateException("Raw readings do not match sensor type " + sensorType);
        }

        return SensorSummaryAggregate.numeric(
                sensorType,
                sourceSampleCount,
                rawAggregate.getNumericSum(),
                rawAggregate.getNumericMinimum(),
                rawAggregate.getNumericMaximum());
    }



    private void verifyHourlySummary(
            Sensor sensor,
            HourlySensorSummary summary,
            Instant bucketStart,
            SensorSummaryAggregate aggregate,
            Instant refreshedAt
    ) {

        Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);

        boolean matchesAggregate =
                summary.getSourceSampleCount()
                        == aggregate.getSourceSampleCount()
                        && summary.getUnit() == aggregate.getUnit()
                        && numericallyEqual(summary.getNumericSum(), aggregate.getNumericSum())
                        && Objects.equals(summary.getNumericMinimum(), aggregate.getNumericMinimum())
                        && Objects.equals(summary.getNumericMaximum(), aggregate.getNumericMaximum())
                        && Objects.equals(summary.getTrueSampleCount(), aggregate.getTrueSampleCount());

        boolean matchesBucket =
                Objects.equals(summary.getSensor().getId(), sensor.getId())
                        && bucketStart.equals(summary.getBucketStart())
                        && bucketEnd.equals(summary.getBucketEnd())
                        && refreshedAt.equals(summary.getRefreshedAt());

        if (!matchesAggregate || !matchesBucket) {
            throw new IllegalStateException("Persisted hourly summary does not match its source aggregate");
        }
    }



    private static boolean numericallyEqual(BigDecimal first, BigDecimal second) {

        if (first == null || second == null) {
            return first == second;
        }

        return first.compareTo(second) == 0;
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