package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
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
    private final StatisticsResponseMapper responseMapper;
    private final Clock clock;

    public StatisticsQueryServiceImpl(SensorRepository sensorRepository,StatisticsAvailabilityResolver availabilityResolver,StatisticsSeriesMaterializer seriesMaterializer,StatisticsQueryPolicy queryPolicy,StatisticsResolutionPolicy resolutionPolicy,StatisticsResponseMapper responseMapper,Clock clock) {
        this.sensorRepository = sensorRepository;
        this.availabilityResolver = availabilityResolver;
        this.seriesMaterializer = seriesMaterializer;
        this.queryPolicy = queryPolicy;
        this.resolutionPolicy = resolutionPolicy;
        this.responseMapper = responseMapper;
        this.clock = clock;
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



        StatisticsRequestContext request = resolveRequest(
                sensorId,
                ownerId,
                startInclusive,
                endExclusive,
                requestedResolution,
                temperatureUnit);

        Sensor sensor = request.sensor();
        StatisticsResolution requested = request.requestedResolution();
        TemperatureUnit effectiveTemperatureUnit = request.temperatureUnit();
        StatisticsQueryWindow window = request.window();
        ZoneId timeZone = request.timeZone();
        StatisticsAvailabilitySnapshot availability = request.availability();

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
                .map(point -> responseMapper.toPointDTO(sensor.getType(),point,displayGranularity,effectiveTemperatureUnit))
                .toList();

        return new SensorStatisticsSeriesDTO(
                responseMapper.toSensorDTO(sensor,effectiveTemperatureUnit),
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
                responseMapper.toCsvExportAvailabilityDTO(materialized,queryPolicy.getCsvExportRowLimit()),
                responseMapper.toCoverageDTO(availability),
                periodMetrics,
                points);
    }




    @Override
    @Transactional(readOnly = true,isolation = Isolation.REPEATABLE_READ)
    public SensorStatisticsExportDTO getSummaryExport(
            Long sensorId,
            Long ownerId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsResolution resolution,
            TemperatureUnit temperatureUnit
    ) {
        StatisticsRequestContext request = resolveRequest(
                sensorId,
                ownerId,
                startInclusive,
                endExclusive,
                resolution,
                temperatureUnit);

        StatisticsResolution requestedResolution = request.requestedResolution();

        if (requestedResolution == StatisticsResolution.RAW) {
            throw new InvalidStatisticsQueryException(
                    "Raw-reading CSV export is not available");
        }

        StatisticsMaterializedExport materialized =
                seriesMaterializer.materializeSummaryExport(
                        request.sensor(),
                        request.window(),
                        request.timeZone(),
                        requestedResolution,
                        request.availability());

        StatisticsResolution resolvedResolution =
                materialized.resolvedResolution();

        StatisticsDisplayGranularity granularity =
                switch (resolvedResolution) {
                    case HOURLY -> StatisticsDisplayGranularity.HOURLY;
                    case DAILY -> StatisticsDisplayGranularity.DAILY;
                    case AUTO,RAW -> throw new IllegalStateException(
                            "Summary export resolved to a non-summary resolution");
                };

        List<StatisticsSeriesPointDTO> rows = materialized.rows().stream()
                .map(point ->
                        responseMapper.toPointDTO(request.sensor().getType(),
                                point,
                                granularity,
                                request.temperatureUnit()))
                .toList();

        StatisticsQueryWindow window = request.window();

        return new SensorStatisticsExportDTO(
                responseMapper.toSensorDTO(request.sensor(),request.temperatureUnit()),
                window.evaluated().startInclusive(),
                window.evaluated().endExclusive(),
                resolvedResolution,
                rows);
    }

    private StatisticsRequestContext resolveRequest(
            Long sensorId,
            Long ownerId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsResolution requestedResolution,
            TemperatureUnit temperatureUnit
    ) {
        Sensor sensor = sensorRepository.findByIdAndOwnerId(sensorId,ownerId)
                .orElseThrow(SensorNotFoundException::new);

        Instant asOf = clock.instant();

        StatisticsResolution effectiveResolution =
                requestedResolution == null
                        ? StatisticsResolution.AUTO
                        : requestedResolution;

        TemperatureUnit effectiveTemperatureUnit =
                temperatureUnit == null
                        ? TemperatureUnit.CELSIUS
                        : temperatureUnit;

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(
                startInclusive,
                endExclusive,
                asOf,
                queryPolicy.getMaximumRange());

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());

        StatisticsAvailabilitySnapshot availability =
                availabilityResolver.resolve(sensor,window.asOf());

        return new StatisticsRequestContext(
                sensor,
                effectiveResolution,
                effectiveTemperatureUnit,
                window,
                timeZone,
                availability);
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
                : SensorSummaryAggregator.combine(sensorType,availableAggregates);

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



    private StatisticsPeriodMetricsDTO buildPeriodMetrics(SensorType sensorType,List<StatisticsDataPoint> sourcePoints,StatisticsRangeConditionsDTO conditions,TemperatureUnit temperatureUnit) {
        boolean unavailable = conditions.containsExpiredIntervals()
                || conditions.containsRollupDelayedIntervals();

        if (unavailable) {
            return responseMapper.toPeriodMetricsDTO(sensorType,null,temperatureUnit);
        }

        SensorSummaryAggregate periodAggregate = sourcePoints.isEmpty()
                ? SensorSummaryAggregator.empty(sensorType)
                : SensorSummaryAggregator.combine(sensorType,sourcePoints.stream().map(StatisticsDataPoint::aggregate).toList());

        return responseMapper.toPeriodMetricsDTO(sensorType,periodAggregate,temperatureUnit);
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









    private StatisticsPointStatus pointStatusForAggregate(SensorSummaryAggregate aggregate) {

        return aggregate.getSourceSampleCount() == 0
                ? StatisticsPointStatus.NO_SAMPLES
                : StatisticsPointStatus.COMPLETE;
    }



    private boolean containsStatus(List<? extends StatisticsDataPoint> points, StatisticsPointStatus status) {

        return points.stream().anyMatch(point -> point.status() == status);
    }



    private record StatisticsRequestContext(
            Sensor sensor,
            StatisticsResolution requestedResolution,
            TemperatureUnit temperatureUnit,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsAvailabilitySnapshot availability
    ) {
    }

}