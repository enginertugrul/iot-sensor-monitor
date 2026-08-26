package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;




@Service
public class StatisticsQueryServiceImpl implements StatisticsQueryService {


    private final SensorRepository sensorRepository;
    private final StatisticsAvailabilityResolver availabilityResolver;
    private final StatisticsSeriesMaterializer seriesMaterializer;
    private final StatisticsQueryPolicy queryPolicy;
    private final StatisticsResolutionPolicy resolutionPolicy;
    private final StatisticsAggregationPolicy aggregationPolicy;
    private final TemperatureUnitConverter temperatureUnitConverter;


    public StatisticsQueryServiceImpl(SensorRepository sensorRepository, StatisticsAvailabilityResolver availabilityResolver, StatisticsSeriesMaterializer seriesMaterializer, StatisticsQueryPolicy queryPolicy, StatisticsResolutionPolicy resolutionPolicy, StatisticsAggregationPolicy aggregationPolicy, TemperatureUnitConverter temperatureUnitConverter) {
        this.sensorRepository = sensorRepository;
        this.availabilityResolver = availabilityResolver;
        this.seriesMaterializer = seriesMaterializer;
        this.queryPolicy = queryPolicy;
        this.resolutionPolicy = resolutionPolicy;
        this.aggregationPolicy = aggregationPolicy;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }



    @Override
    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public SensorStatisticsSeriesDTO getSeries(
            Long sensorId,
            Long ownerId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsResolution requestedResolution,
            TemperatureUnit temperatureUnit
    ) {

        Sensor sensor = sensorRepository.findByIdAndOwnerId(sensorId,ownerId)
                .orElseThrow(SensorNotFoundException::new);

        Instant asOf = Instant.now();

        StatisticsResolution requested = requestedResolution == null
                ? StatisticsResolution.AUTO
                : requestedResolution;

        TemperatureUnit effectiveTemperatureUnit = temperatureUnit == null
                ? TemperatureUnit.CELSIUS
                : temperatureUnit;

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(
                startInclusive,
                endExclusive,
                asOf,
                queryPolicy.getMaximumRange());

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());

        StatisticsAvailabilitySnapshot availability = availabilityResolver.resolve(sensor, window.asOf());

        StatisticsMaterializedSeries materialized = seriesMaterializer.materialize(
                sensor,
                window,
                timeZone,
                requested,
                availability);

        StatisticsDisplayGranularity displayGranularity =
                resolutionPolicy.resolveDisplayGranularity(
                        materialized.resolvedResolution(),
                        window.firstLocalDate(timeZone),
                        window.lastLocalDate(timeZone));

        List<StatisticsDataPoint> displayPoints =
                materialized.resolvedResolution() == StatisticsResolution.DAILY
                        ? groupDailyPoints(
                        materialized.sourcePoints(),
                        displayGranularity,
                        sensor.getType())
                        : materialized.sourcePoints();

        if (displayPoints.size() > queryPolicy.getChartPointBudget()) {
            throw new IllegalStateException("Resolved statistics series exceeds the configured point budget");
        }

        StatisticsRangeConditionsDTO rangeConditions = determineRangeConditions(materialized, window.endClippedToAsOf());

        StatisticsPeriodMetricsDTO periodMetrics = buildPeriodMetrics(
                sensor.getType(),
                materialized.sourcePoints(),
                rangeConditions,
                effectiveTemperatureUnit);

        StatisticsRangeStatus rangeStatus = determineRangeStatus(
                materialized,
                periodMetrics,
                rangeConditions);

        boolean fullyCovered = rangeConditions.fullyCovered();

        List<StatisticsSeriesPointDTO> points = displayPoints.stream()
                .map(point -> toPointDTO(
                        sensor.getType(),
                        point,
                        displayGranularity,
                        effectiveTemperatureUnit))
                .toList();

        return new SensorStatisticsSeriesDTO(
                toSensorDTO(sensor,effectiveTemperatureUnit),
                window.requested().startInclusive(),
                window.requested().endExclusive(),
                window.evaluated().startInclusive(),
                window.evaluated().endExclusive(),
                window.asOf(),
                requested,
                materialized.resolvedResolution(),
                displayGranularity,
                rangeStatus,
                rangeConditions,
                fullyCovered,
                queryPolicy.getChartPointBudget(),
                toCoverageDTO(availability),
                periodMetrics,
                points);
    }




    private StatisticsCoverageDTO toCoverageDTO(StatisticsAvailabilitySnapshot availability) {

        return new StatisticsCoverageDTO(
                toTierCoverageDTO(availability.raw()),
                toTierCoverageDTO(availability.hourly()),
                toTierCoverageDTO(availability.daily()));
    }




    private StatisticsTierCoverageDTO toTierCoverageDTO(StatisticsTierAvailability availability) {

        Instant representedFrom = availability.representedCoverage()
                .map(InstantRange::startInclusive)
                .orElse(null);

        Instant representedUntil = availability.representedCoverage()
                .map(InstantRange::endExclusive)
                .orElse(null);

        StatisticsRollupProgressDTO rollupProgressDTO = availability
                .rollupProgress()
                .map(this::toRollupProgressDTO)
                .orElse(null);

        return new StatisticsTierCoverageDTO(
                availability.resolution(),
                availability.retention().retentionWindow().startInclusive(),
                representedFrom,
                representedUntil,
                rollupProgressDTO);
    }




    private StatisticsRollupProgressDTO toRollupProgressDTO(StatisticsRollupProgress progress) {

        Instant verifiedFrom = progress.verifiedCoverage()
                .map(InstantRange::startInclusive)
                .orElse(null);

        Instant safeThrough = progress.verifiedCoverage()
                .map(InstantRange::endExclusive)
                .orElse(null);

        long lagSeconds = progress.lag().getSeconds();

        return new StatisticsRollupProgressDTO(
                verifiedFrom,
                safeThrough,
                progress.rollupDueUntilExclusive(),
                lagSeconds,
                lagSeconds > 0);
    }




    private List<StatisticsDataPoint> groupDailyPoints(
            List<StatisticsDataPoint> dailyPoints,
            StatisticsDisplayGranularity displayGranularity,
            SensorType sensorType
    ) {

        if (displayGranularity == StatisticsDisplayGranularity.DAILY) {
            return dailyPoints;
        }

        Map<Object,List<IntervalStatisticsDataPoint>> groupedPoints =
                new LinkedHashMap<>();

        for (StatisticsDataPoint point : dailyPoints) {

            if (!(point instanceof IntervalStatisticsDataPoint interval)) {
                throw new IllegalStateException("Daily source series contains a raw point");
            }

            LocalDate localDate = interval.localDateStart();

            Object groupingKey = switch (displayGranularity) {
                case WEEKLY -> localDate.with(
                        TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                case MONTHLY -> YearMonth.from(localDate);
                default -> throw new IllegalArgumentException(
                        "Daily points cannot be grouped as " + displayGranularity);
            };

            groupedPoints
                    .computeIfAbsent(groupingKey,ignored -> new ArrayList<>())
                    .add(interval);
        }

        List<StatisticsDataPoint> result = new ArrayList<>(groupedPoints.size());

        for (List<IntervalStatisticsDataPoint> group : groupedPoints.values()) {
            result.add(combineDisplayGroup(sensorType,group));
        }

        return List.copyOf(result);
    }





    private IntervalStatisticsDataPoint combineDisplayGroup(
            SensorType sensorType,
            List<IntervalStatisticsDataPoint> group
    ) {

        IntervalStatisticsDataPoint first = group.getFirst();
        IntervalStatisticsDataPoint last = group.getLast();

        List<SensorSummaryAggregate> availableAggregates = group.stream()
                .map(IntervalStatisticsDataPoint::aggregate)
                .filter(Objects::nonNull)
                .toList();

        SensorSummaryAggregate aggregate = availableAggregates.isEmpty()
                ? null
                : aggregationPolicy.combine(sensorType,availableAggregates);

        StatisticsPointStatus status = determineCombinedPointStatus(group,aggregate);

        boolean completeMetadata =
                (status == StatisticsPointStatus.COMPLETE
                        || status == StatisticsPointStatus.NO_SAMPLES)
                        && group.stream().allMatch(point ->
                        point.finalizedAt() != null
                                && point.refreshedAt() != null);

        Instant finalizedAt = completeMetadata
                ? group.stream()
                .map(IntervalStatisticsDataPoint::finalizedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                : null;

        Instant refreshedAt = completeMetadata
                ? group.stream()
                .map(IntervalStatisticsDataPoint::refreshedAt)
                .max(Comparator.naturalOrder())
                .orElseThrow()
                : null;

        return new IntervalStatisticsDataPoint(
                new InstantRange(
                        first.interval().startInclusive(),
                        last.interval().endExclusive()),
                first.localDateStart(),
                last.localDateEndExclusive(),
                first.timeZoneId(),
                status,
                aggregate,
                finalizedAt,
                refreshedAt);
    }



    private StatisticsPointStatus determineCombinedPointStatus(
            List<? extends StatisticsDataPoint> points,
            SensorSummaryAggregate aggregate
    ) {

        boolean containsExpired = containsStatus(points,StatisticsPointStatus.EXPIRED);

        boolean containsRollupDelay = containsStatus(points,StatisticsPointStatus.ROLLUP_DELAY);

        if (aggregate == null) {
            if (containsExpired) {
                return StatisticsPointStatus.EXPIRED;
            }

            if (containsRollupDelay) {
                return StatisticsPointStatus.ROLLUP_DELAY;
            }

            throw new IllegalStateException("A display group without an aggregate must contain an unavailable interval");
        }

        if (containsExpired
                || containsRollupDelay
                || containsStatus(points,StatisticsPointStatus.PARTIAL)) {

            return StatisticsPointStatus.PARTIAL;
        }

        return pointStatusForAggregate(aggregate);
    }





    private StatisticsRangeConditionsDTO determineRangeConditions(StatisticsMaterializedSeries materialized, boolean endClipped) {

        boolean containsExpiredIntervals =
                materialized.resolvedResolution() == StatisticsResolution.RAW
                        ? materialized.rawRangeAvailability() != RawRangeAvailability.FULL
                        : containsStatus(
                        materialized.sourcePoints(),
                        StatisticsPointStatus.EXPIRED);

        boolean containsRollupDelayedIntervals = containsStatus(
                materialized.sourcePoints(),
                StatisticsPointStatus.ROLLUP_DELAY);

        boolean containsIncompleteIntervals = endClipped
                || containsStatus(
                materialized.sourcePoints(),
                StatisticsPointStatus.PARTIAL);

        return new StatisticsRangeConditionsDTO(
                containsExpiredIntervals,
                containsRollupDelayedIntervals,
                containsIncompleteIntervals);
    }



    private StatisticsPeriodMetricsDTO buildPeriodMetrics(
            SensorType sensorType,
            List<StatisticsDataPoint> sourcePoints,
            StatisticsRangeConditionsDTO conditions,
            TemperatureUnit temperatureUnit
    ) {

        boolean unavailable = conditions.containsExpiredIntervals()
                || conditions.containsRollupDelayedIntervals();

        if (unavailable) {
            return new StatisticsPeriodMetricsDTO(false,0,null,null);
        }

        SensorSummaryAggregate periodAggregate = sourcePoints.isEmpty()
                ? aggregationPolicy.empty(sensorType)
                : aggregationPolicy.combine(
                sensorType,
                sourcePoints.stream()
                        .map(StatisticsDataPoint::aggregate)
                        .toList());

        StatisticsNumericMetricsDTO numericMetrics = null;
        StatisticsMotionMetricsDTO motionMetrics = null;

        if (SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
            numericMetrics = aggregationPolicy.toNumericMetrics(
                    sensorType,
                    periodAggregate,
                    temperatureUnit);
        } else {
            motionMetrics = aggregationPolicy.toMotionMetrics(periodAggregate);
        }

        return new StatisticsPeriodMetricsDTO(
                true,
                periodAggregate.getSourceSampleCount(),
                numericMetrics,
                motionMetrics);
    }





    private StatisticsRangeStatus determineRangeStatus(
            StatisticsMaterializedSeries materialized,
            StatisticsPeriodMetricsDTO periodMetrics,
            StatisticsRangeConditionsDTO conditions
    ) {

        if (conditions.containsExpiredIntervals()) {
            boolean hasNonExpired;

            if (materialized.resolvedResolution() == StatisticsResolution.RAW) {
                hasNonExpired = materialized.rawRangeAvailability()
                        != RawRangeAvailability.EXPIRED;
            } else {
                hasNonExpired = materialized.sourcePoints().stream()
                        .anyMatch(point ->
                                point.status() != StatisticsPointStatus.EXPIRED);
            }

            return hasNonExpired
                    ? StatisticsRangeStatus.PARTIALLY_EXPIRED
                    : StatisticsRangeStatus.EXPIRED;
        }

        if (conditions.containsRollupDelayedIntervals()) {
            return StatisticsRangeStatus.ROLLUP_DELAY;
        }

        if (conditions.containsIncompleteIntervals()) {
            return StatisticsRangeStatus.PARTIAL;
        }

        if (periodMetrics.available()
                && periodMetrics.sourceSampleCount() == 0) {

            return StatisticsRangeStatus.NO_SAMPLES;
        }

        return StatisticsRangeStatus.COMPLETE;
    }





    private StatisticsSeriesPointDTO toPointDTO(
            SensorType sensorType,
            StatisticsDataPoint point,
            StatisticsDisplayGranularity displayGranularity,
            TemperatureUnit temperatureUnit
    ) {

        SensorSummaryAggregate aggregate = point.aggregate();

        StatisticsNumericMetricsDTO numericMetrics = null;
        StatisticsMotionMetricsDTO motionMetrics = null;
        Long sourceSampleCount = null;

        if (aggregate != null) {
            sourceSampleCount = aggregate.getSourceSampleCount();

            if (SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
                numericMetrics = aggregationPolicy.toNumericMetrics(
                        sensorType,
                        aggregate,
                        temperatureUnit);
            } else {
                motionMetrics = aggregationPolicy.toMotionMetrics(aggregate);
            }
        }

        if (point instanceof RawStatisticsDataPoint rawPoint) {
            return new StatisticsSeriesPointDTO(
                    StatisticsDisplayGranularity.RAW,
                    rawPoint.sourceReadingId(),
                    rawPoint.recordedAt(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    rawPoint.status(),
                    sourceSampleCount,
                    numericMetrics,
                    motionMetrics,
                    null,
                    null);
        }

        IntervalStatisticsDataPoint intervalPoint = (IntervalStatisticsDataPoint) point;

        return new StatisticsSeriesPointDTO(
                displayGranularity,
                null,
                null,
                intervalPoint.interval().startInclusive(),
                intervalPoint.interval().endExclusive(),
                intervalPoint.localDateStart(),
                intervalPoint.localDateEndExclusive(),
                intervalPoint.timeZoneId(),
                intervalPoint.status(),
                sourceSampleCount,
                numericMetrics,
                motionMetrics,
                intervalPoint.finalizedAt(),
                intervalPoint.refreshedAt());
    }




    private StatisticsSensorDTO toSensorDTO(Sensor sensor, TemperatureUnit temperatureUnit) {

        SensorType sensorType = sensor.getType();

        MeasurementUnit canonicalUnit =
                SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)
                        ? SensorMeasurementPolicy.requireCanonicalUnit(sensorType)
                        : null;

        String displayUnit = null;
        String displayUnitSymbol = null;

        switch (sensorType) {
            case TEMPERATURE -> {
                displayUnit = temperatureUnit.name();
                displayUnitSymbol = temperatureUnitConverter.getSymbol(temperatureUnit);
            }

            case HUMIDITY -> {
                displayUnit = canonicalUnit.name();
                displayUnitSymbol = "% RH";
            }

            case MOTION -> {
            }
        }

        return new StatisticsSensorDTO(
                sensor.getId(),
                sensor.getName(),
                sensorType,
                sensor.getTimezone(),
                canonicalUnit,
                displayUnit,
                displayUnitSymbol);
    }




    private StatisticsPointStatus pointStatusForAggregate(SensorSummaryAggregate aggregate) {

        return aggregate.getSourceSampleCount() == 0
                ? StatisticsPointStatus.NO_SAMPLES
                : StatisticsPointStatus.COMPLETE;
    }



    private boolean containsStatus(List<? extends StatisticsDataPoint> points, StatisticsPointStatus status) {

        return points.stream().anyMatch(point -> point.status() == status);
    }

}