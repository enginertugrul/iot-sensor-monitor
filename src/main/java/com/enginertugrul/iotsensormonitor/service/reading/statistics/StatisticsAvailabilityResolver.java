package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;




@Component
public class StatisticsAvailabilityResolver {


    private final SensorRollupCheckpointRepository checkpointRepository;
    private final SensorDataLifecyclePolicy lifecyclePolicy;


    public StatisticsAvailabilityResolver(SensorRollupCheckpointRepository checkpointRepository, SensorDataLifecyclePolicy lifecyclePolicy) {
        this.checkpointRepository = checkpointRepository;
        this.lifecyclePolicy = lifecyclePolicy;
    }



    StatisticsAvailabilitySnapshot resolve(Sensor sensor,Instant asOf) {

        Objects.requireNonNull(sensor,"sensor must not be null");
        Objects.requireNonNull(asOf,"asOf must not be null");


        if (asOf.isBefore(sensor.getCreatedAt())) {
            throw new IllegalStateException("Statistics snapshot cannot precede sensor creation");
        }

        SensorHistory history = SensorHistory.from(sensor);
        ZoneId timeZone = ZoneId.of(sensor.getTimezone());

        SensorRollupCheckpoint hourlyCheckpoint = checkpointRepository
                .findBySensorIdAndStage(sensor.getId(),RollupStage.RAW_TO_HOURLY)
                .orElse(null);

        SensorRollupCheckpoint dailyCheckpoint = checkpointRepository
                .findBySensorIdAndStage(sensor.getId(),RollupStage.HOURLY_TO_DAILY)
                .orElse(null);

        StatisticsTierAvailability raw = resolveRaw(sensor,asOf);
        StatisticsTierAvailability hourly = resolveHourly(history,hourlyCheckpoint,asOf);
        StatisticsTierAvailability daily = resolveDaily(
                history,
                dailyCheckpoint,
                timeZone,
                asOf);

        return new StatisticsAvailabilitySnapshot(history,raw,hourly,daily);
    }





    private StatisticsTierAvailability resolveRaw(Sensor sensor,Instant asOf) {

        Instant expirationCutoff = asOf
                .minus(lifecyclePolicy.getRawRetention())
                .truncatedTo(ChronoUnit.HOURS);

        Instant retentionWindowStart = laterOf(sensor.getCreatedAt(),expirationCutoff);
        InstantRange retentionWindow = new InstantRange(retentionWindowStart,asOf);

        StatisticsTierRetention retention = new StatisticsTierRetention(expirationCutoff,retentionWindow);

        Optional<InstantRange> representedCoverage = retentionWindow.isEmpty()
                ? Optional.empty()
                : Optional.of(retentionWindow);

        return new StatisticsTierAvailability(
                StatisticsResolution.RAW,
                retention,
                representedCoverage,
                Optional.empty());
    }





    private StatisticsTierAvailability resolveHourly(
            SensorHistory history,
            SensorRollupCheckpoint checkpoint,
            Instant asOf
    ) {
        Instant expirationCutoff = asOf
                .minus(lifecyclePolicy.getHourlyRetention())
                .truncatedTo(ChronoUnit.HOURS);

        Instant rollupDueUntil = asOf
                .minus(lifecyclePolicy.getHourlyRollupGrace())
                .truncatedTo(ChronoUnit.HOURS);

        StatisticsTierRetention retention = new StatisticsTierRetention(
                expirationCutoff,
                new InstantRange(expirationCutoff,asOf));

        StatisticsRollupProgress progress = resolveRollupProgress(
                RollupStage.RAW_TO_HOURLY,
                history.hourlyCoverageOrigin(),
                checkpoint,
                rollupDueUntil);

        return summaryAvailability(
                StatisticsResolution.HOURLY,
                retention,
                progress);
    }




    private StatisticsTierAvailability resolveDaily(
            SensorHistory history,
            SensorRollupCheckpoint checkpoint,
            ZoneId timeZone,
            Instant asOf
    ) {

        Instant expirationCutoff = asOf.minus(lifecyclePolicy.getDailyRetention());

        Instant retentionWindowStart = expirationCutoff
                .atZone(timeZone)
                .toLocalDate()
                .atStartOfDay(timeZone)
                .toInstant();

        Instant rollupDueUntil = asOf
                .minus(lifecyclePolicy.getDailyRollupGrace())
                .atZone(timeZone)
                .toLocalDate()
                .atStartOfDay(timeZone)
                .toInstant();

        StatisticsTierRetention retention = new StatisticsTierRetention(
                expirationCutoff,
                new InstantRange(retentionWindowStart,asOf));

        StatisticsRollupProgress progress = resolveRollupProgress(
                RollupStage.HOURLY_TO_DAILY,
                history.dailyCoverageOrigin(timeZone),
                checkpoint,
                rollupDueUntil);

        return summaryAvailability(
                StatisticsResolution.DAILY,
                retention,
                progress);
    }




    private StatisticsTierAvailability summaryAvailability(
            StatisticsResolution resolution,
            StatisticsTierRetention retention,
            StatisticsRollupProgress progress
    ) {

        Optional<InstantRange> queryableCoverage = progress
                .verifiedCoverage()
                .flatMap(verified ->
                        verified.intersection(retention.retentionWindow()));

        return new StatisticsTierAvailability(
                resolution,
                retention,
                queryableCoverage,
                Optional.of(progress));
    }




    private StatisticsRollupProgress resolveRollupProgress(
            RollupStage expectedStage,
            Optional<Instant> expectedCoverageOrigin,
            SensorRollupCheckpoint checkpoint,
            Instant rollupDueUntil
    ) {

        Optional<InstantRange> verifiedCoverage = Optional.empty();
        Optional<Instant> progressAt = expectedCoverageOrigin;

        if (checkpoint != null) {
            if (checkpoint.getStage() != expectedStage) {
                throw new IllegalStateException("Unexpected rollup checkpoint stage");
            }

            Instant expectedOrigin = expectedCoverageOrigin.orElseThrow(() ->
                    new IllegalStateException("A rollup checkpoint exists for a sensor with no first reading"));

            if (!checkpoint.getCoverageStartedAt().equals(expectedOrigin)) {
                throw new IllegalStateException("Checkpoint coverage origin does not match the sensor's first reading");
            }

            verifiedCoverage = Optional.of(new InstantRange(
                    checkpoint.getCoverageStartedAt(),
                    checkpoint.getCoveredUntil()));

            progressAt = Optional.of(checkpoint.getCoveredUntil());
        }

        Duration lag = progressAt
                .filter(progress -> progress.isBefore(rollupDueUntil))
                .map(progress -> Duration.between(progress,rollupDueUntil))
                .orElse(Duration.ZERO);

        return new StatisticsRollupProgress(
                verifiedCoverage,
                rollupDueUntil,
                lag);
    }


    private static Instant laterOf(Instant first,Instant second) {
        return first.isAfter(second) ? first : second;
    }
}