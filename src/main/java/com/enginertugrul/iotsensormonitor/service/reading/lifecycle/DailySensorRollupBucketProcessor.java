package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.*;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;




@Service
public class DailySensorRollupBucketProcessor {


    @PersistenceContext
    private EntityManager entityManager;

    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;
    private final DailySensorSummaryRepository dailySensorSummaryRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;



    public DailySensorRollupBucketProcessor( SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, DailySensorSummaryRepository dailySensorSummaryRepository, SensorRollupCheckpointRepository checkpointRepository) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.dailySensorSummaryRepository = dailySensorSummaryRepository;
        this.checkpointRepository = checkpointRepository;
    }






    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DailyRollupBucketResult advanceNextClosedDay(RollupSensorProjection sensor, Instant eligibleBucketEnd) {

        RollupSensorProjection requiredSensor = Objects.requireNonNull(sensor,"sensor must not be null");
        Long sensorId = requiredSensor.getId();
        Instant requiredEligibleBucketEnd = Objects.requireNonNull(eligibleBucketEnd,"eligibleBucketEnd must not be null");


        ZoneId timeZone = ZoneId.of(requiredSensor.getTimezone());

        Optional<SensorRollupCheckpoint> hourlyCoverageCheckpointCandidate = checkpointRepository.findBySensorIdAndStageForUpdate(sensorId, RollupStage.RAW_TO_HOURLY);


        if (hourlyCoverageCheckpointCandidate.isEmpty()) {
            return waitingForHourlyCheckpointResult(sensorId, timeZone);
        }

        SensorRollupCheckpoint hourlyCoverageCheckpoint = hourlyCoverageCheckpointCandidate.get();
        SensorRollupCheckpoint dailyCoverageCheckpoint = loadOrInitializeDailyCoverageCheckpoint(sensor, hourlyCoverageCheckpoint);

        LocalDayBucket bucket = nextLocalDayBucket(dailyCoverageCheckpoint,timeZone);
        Instant requiredHourlyCoveredUntil = utcHourAtOrAfter(bucket.end());

        if (bucket.end().isAfter(requiredEligibleBucketEnd)) {
            return upToDateResult(
                    sensorId,
                    timeZone,
                    bucket,
                    dailyCoverageCheckpoint,
                    requiredHourlyCoveredUntil,
                    hourlyCoverageCheckpoint);
        }

        if (hourlyCoverageCheckpoint.getCoveredUntil().isBefore(requiredHourlyCoveredUntil)) {
            return waitingForHourlyCoverageResult(
                    sensorId,
                    timeZone,
                    bucket,
                    dailyCoverageCheckpoint,
                    requiredHourlyCoveredUntil,
                    hourlyCoverageCheckpoint);
        }

        Instant attemptedAt = notBefore(Instant.now(),dailyCoverageCheckpoint.getUpdatedAt());
        attemptedAt = notBefore(attemptedAt,hourlyCoverageCheckpoint.getUpdatedAt());
        attemptedAt = notBefore(attemptedAt,bucket.end());
        dailyCoverageCheckpoint.recordAttempt(bucket.start(),attemptedAt);

        DailyAggregateSource source = aggregateDailySource(requiredSensor, bucket, hourlyCoverageCheckpoint);

        Instant completedAt = notBefore(Instant.now(),attemptedAt);

        completedAt = saveOrRefreshDailySummary(
                sensorId,
                timeZone,
                bucket,
                source.aggregate(),
                completedAt);

        dailyCoverageCheckpoint.advanceContiguously(bucket.start(), bucket.end(), completedAt);


        checkpointRepository.saveAndFlush(dailyCoverageCheckpoint);

        return advancedResult(
                sensorId,
                timeZone,
                bucket,
                dailyCoverageCheckpoint,
                requiredHourlyCoveredUntil,
                hourlyCoverageCheckpoint,
                source);
    }




    private SensorRollupCheckpoint loadOrInitializeDailyCoverageCheckpoint(RollupSensorProjection sensor, SensorRollupCheckpoint hourlyCoverageCheckpoint) {

        return checkpointRepository.findBySensorIdAndStageForUpdate(
                        sensor.getId(),
                        RollupStage.HOURLY_TO_DAILY)
                .orElseGet(() -> initializeDailyCoverageCheckpoint(sensor,hourlyCoverageCheckpoint) );

    }




    private SensorRollupCheckpoint initializeDailyCoverageCheckpoint(RollupSensorProjection sensor, SensorRollupCheckpoint hourlyCoverageCheckpoint) {

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());

        Instant firstReadingAt = sensor.getFirstReadingAt();

        Instant expectedHourlyCoverageStart = firstReadingAt.truncatedTo(ChronoUnit.HOURS);

        if (!expectedHourlyCoverageStart.equals(hourlyCoverageCheckpoint.getCoverageStartedAt())) {
            throw new IllegalStateException("Hourly coverage checkpoint does not begin at the sensor's first recorded UTC hour");
        }

        LocalDate firstReadingLocalDate = firstReadingAt.atZone(timeZone).toLocalDate();

        Instant dailyCoverageStart = firstReadingLocalDate.atStartOfDay(timeZone).toInstant();
        Instant initializedAt = notBefore(Instant.now(),dailyCoverageStart);
        initializedAt = notBefore(initializedAt,hourlyCoverageCheckpoint.getUpdatedAt());

        Sensor sensorReference = entityManager.getReference(Sensor.class, sensor.getId());

        SensorRollupCheckpoint checkpoint =
                SensorRollupCheckpoint.initialize(
                        sensorReference,
                        RollupStage.HOURLY_TO_DAILY,
                        dailyCoverageStart,
                        initializedAt);

        return checkpointRepository.saveAndFlush(checkpoint);
    }






    private DailyAggregateSource aggregateDailySource(RollupSensorProjection sensor, LocalDayBucket bucket, SensorRollupCheckpoint hourlyCoverageCheckpoint) {

        Instant firstFullUtcHourStart = utcHourAtOrAfter(bucket.start());

        Instant fullUtcHoursEnd = bucket.end().truncatedTo(ChronoUnit.HOURS);

        Instant hourlyRangeStart = notBefore(firstFullUtcHourStart, hourlyCoverageCheckpoint.getCoverageStartedAt());

        if (!hourlyRangeStart.isBefore(fullUtcHoursEnd)) {
            SensorSummaryAggregate rawAggregate = aggregateRawRange(sensor, bucket.start(), bucket.end());

            return new DailyAggregateSource(rawAggregate, 0, rawAggregate.getSourceSampleCount());
        }

        List<SensorSummaryAggregate> aggregateParts = new ArrayList<>();
        long rawBoundarySampleCount = 0;

        if (bucket.start().isBefore(hourlyRangeStart)) {

            SensorSummaryAggregate leadingRawAggregate = aggregateRawRange(sensor, bucket.start(), hourlyRangeStart);

            aggregateParts.add(leadingRawAggregate);

            rawBoundarySampleCount = Math.addExact(rawBoundarySampleCount, leadingRawAggregate.getSourceSampleCount());
        }

        List<SensorSummaryAggregate> hourlyAggregates = loadHourlyAggregates(sensor.getId(), hourlyRangeStart, fullUtcHoursEnd);

        aggregateParts.addAll(hourlyAggregates);

        if (fullUtcHoursEnd.isBefore(bucket.end())) {

            SensorSummaryAggregate trailingRawAggregate = aggregateRawRange(sensor, fullUtcHoursEnd, bucket.end());

            aggregateParts.add(trailingRawAggregate);
            rawBoundarySampleCount = Math.addExact(rawBoundarySampleCount, trailingRawAggregate.getSourceSampleCount());
        }

        SensorSummaryAggregate combinedAggregate = SensorSummaryAggregator.combine(sensor.getType(), aggregateParts);

        return new DailyAggregateSource(combinedAggregate, hourlyAggregates.size(), rawBoundarySampleCount);
    }





    private List<SensorSummaryAggregate> loadHourlyAggregates(Long sensorId, Instant startInclusive, Instant endExclusive) {

        List<HourlySensorSummary> summaries =
                hourlySensorSummaryRepository.findForDailyRollup(
                        sensorId,
                        startInclusive,
                        endExclusive);

        long expectedSummaryCount = ChronoUnit.HOURS.between(startInclusive,endExclusive);

        if (summaries.size() != expectedSummaryCount) {
            throw new IllegalStateException(
                    "Hourly coverage contains "
                            + expectedSummaryCount
                            + " buckets but "
                            + summaries.size()
                            + " summary rows were found for sensor "
                            + sensorId);
        }

        List<SensorSummaryAggregate> aggregates = new ArrayList<>(summaries.size());

        for (HourlySensorSummary summary : summaries) {
            aggregates.add(summary.toAggregate());
        }

        return aggregates;
    }





    private SensorSummaryAggregate aggregateRawRange(RollupSensorProjection sensor, Instant startInclusive, Instant endExclusive) {

        RawSensorReadingAggregateProjection rawAggregate =
                sensorReadingRepository.aggregateForSummaryRange(
                        sensor.getId(),
                        startInclusive,
                        endExclusive);

        return SensorSummaryAggregator.fromRawReadings(
                sensor.getType(),
                rawAggregate);
    }









    private Instant saveOrRefreshDailySummary(
            Long sensorId,
            ZoneId timeZone,
            LocalDayBucket bucket,
            SensorSummaryAggregate aggregate,
            Instant completedAt
    ) {

        Optional<DailySensorSummary> existingSummary =
                dailySensorSummaryRepository.findBySensorIdAndBucketStart(
                        sensorId,
                        bucket.start());

        DailySensorSummary summary;
        Instant effectiveCompletedAt = completedAt;


        if (existingSummary.isPresent()) {

            summary = existingSummary.get();
            effectiveCompletedAt = notBefore(effectiveCompletedAt,summary.getRefreshedAt());

            summary.refresh(aggregate,effectiveCompletedAt);
        } else {

            Sensor sensor = entityManager.getReference(Sensor.class, sensorId);

            summary = DailySensorSummary.create(
                    sensor,
                    bucket.localDate(),
                    timeZone,
                    aggregate,
                    effectiveCompletedAt);
        }

        dailySensorSummaryRepository.saveAndFlush(summary);
        return effectiveCompletedAt;
    }




    private static LocalDayBucket nextLocalDayBucket(SensorRollupCheckpoint dailyCoverageCheckpoint, ZoneId timeZone) {

        Instant bucketStart = dailyCoverageCheckpoint.getCoveredUntil();

        LocalDate nextUncoveredLocalDate = bucketStart.atZone(timeZone).toLocalDate();

        Instant expectedBucketStart = nextUncoveredLocalDate.atStartOfDay(timeZone).toInstant();

        if (!bucketStart.equals(expectedBucketStart)) {
            throw new IllegalStateException("Daily coverage checkpoint is not aligned to the sensor-local day");
        }

        Instant bucketEnd = nextUncoveredLocalDate.plusDays(1)
                        .atStartOfDay(timeZone)
                        .toInstant();

        return new LocalDayBucket(nextUncoveredLocalDate, bucketStart, bucketEnd);
    }












    private static DailyRollupBucketResult waitingForHourlyCheckpointResult(Long sensorId, ZoneId timeZone) {
        return new DailyRollupBucketResult(
                DailyRollupBucketResult.Status.WAITING_FOR_HOURLY,
                sensorId,
                null,
                timeZone.getId(),
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                0,
                0);
    }





    private static DailyRollupBucketResult upToDateResult(
            Long sensorId,
            ZoneId timeZone,
            LocalDayBucket bucket,
            SensorRollupCheckpoint dailyCoverageCheckpoint,
            Instant requiredHourlyCoveredUntil,
            SensorRollupCheckpoint hourlyCoverageCheckpoint
    ) {
        return new DailyRollupBucketResult(
                DailyRollupBucketResult.Status.UP_TO_DATE,
                sensorId,
                bucket.localDate(),
                timeZone.getId(),
                bucket.start(),
                bucket.end(),
                dailyCoverageCheckpoint.getCoverageStartedAt(),
                dailyCoverageCheckpoint.getCoveredUntil(),
                requiredHourlyCoveredUntil,
                hourlyCoverageCheckpoint.getCoveredUntil(),
                0,
                0,
                0);
    }




    private static DailyRollupBucketResult waitingForHourlyCoverageResult(
            Long sensorId,
            ZoneId timeZone,
            LocalDayBucket bucket,
            SensorRollupCheckpoint dailyCoverageCheckpoint,
            Instant requiredHourlyCoveredUntil,
            SensorRollupCheckpoint hourlyCoverageCheckpoint
    ) {
        return new DailyRollupBucketResult(
                DailyRollupBucketResult.Status.WAITING_FOR_HOURLY,
                sensorId,
                bucket.localDate(),
                timeZone.getId(),
                bucket.start(),
                bucket.end(),
                dailyCoverageCheckpoint.getCoverageStartedAt(),
                dailyCoverageCheckpoint.getCoveredUntil(),
                requiredHourlyCoveredUntil,
                hourlyCoverageCheckpoint.getCoveredUntil(),
                0,
                0,
                0);
    }




    private static DailyRollupBucketResult advancedResult(
            Long sensorId,
            ZoneId timeZone,
            LocalDayBucket bucket,
            SensorRollupCheckpoint dailyCoverageCheckpoint,
            Instant requiredHourlyCoveredUntil,
            SensorRollupCheckpoint hourlyCoverageCheckpoint,
            DailyAggregateSource source
    ) {

        return new DailyRollupBucketResult(
                DailyRollupBucketResult.Status.ADVANCED,
                sensorId,
                bucket.localDate(),
                timeZone.getId(),
                bucket.start(),
                bucket.end(),
                dailyCoverageCheckpoint.getCoverageStartedAt(),
                dailyCoverageCheckpoint.getCoveredUntil(),
                requiredHourlyCoveredUntil,
                hourlyCoverageCheckpoint.getCoveredUntil(),
                source.aggregate().getSourceSampleCount(),
                source.hourlySummaryRows(),
                source.rawBoundarySampleCount());
    }




    private static Instant utcHourAtOrAfter(Instant value) {
        Instant utcHourAtOrBefore = value.truncatedTo(ChronoUnit.HOURS);

        return value.equals(utcHourAtOrBefore)
                ? utcHourAtOrBefore
                : utcHourAtOrBefore.plus(1,ChronoUnit.HOURS);
    }






    private static Instant notBefore(Instant candidate, Instant boundary) {
        return candidate.isBefore(boundary)
                ? boundary
                : candidate;
    }



    private record LocalDayBucket(LocalDate localDate, Instant start, Instant end) {
    }





    private record DailyAggregateSource(SensorSummaryAggregate aggregate, int hourlySummaryRows, long rawBoundarySampleCount) {

    }



}