package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;



@Component
public class StatisticsSeriesMaterializer {


    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;
    private final DailySensorSummaryRepository dailySensorSummaryRepository;
    private final StatisticsQueryPolicy queryPolicy;
    private final StatisticsResolutionPolicy resolutionPolicy;
    private final StatisticsAggregationPolicy aggregationPolicy;



    public StatisticsSeriesMaterializer(SensorReadingRepository sensorReadingRepository, HourlySensorSummaryRepository hourlySensorSummaryRepository, DailySensorSummaryRepository dailySensorSummaryRepository, StatisticsQueryPolicy queryPolicy, StatisticsResolutionPolicy resolutionPolicy, StatisticsAggregationPolicy aggregationPolicy) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.dailySensorSummaryRepository = dailySensorSummaryRepository;
        this.queryPolicy = queryPolicy;
        this.resolutionPolicy = resolutionPolicy;
        this.aggregationPolicy = aggregationPolicy;
    }


    StatisticsMaterializedSeries materialize(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsResolution requestedResolution,
            StatisticsAvailabilitySnapshot availability
    ) {

        RawRangeAvailability rawAvailability = determineRawRangeAvailability(
                window,
                availability.history(),
                availability.raw());

        return switch (requestedResolution) {
            case RAW -> materializeRaw(sensor,window,availability,rawAvailability);
            case HOURLY -> materializeHourly(sensor,window,availability,rawAvailability);
            case DAILY -> materializeDaily(sensor,window,timeZone,availability,rawAvailability);
            case AUTO -> materializeAutomatically(sensor,window,timeZone,availability,rawAvailability);
        };
    }





    StatisticsMaterializedExport materializeSummaryExport(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsResolution resolution,
            StatisticsAvailabilitySnapshot availability
    ) {
        return switch (resolution) {
            case AUTO -> materializeSummaryExportAutomatically(
                    sensor,
                    window,
                    timeZone,
                    availability);
            case HOURLY,DAILY -> materializeSummaryExportAtResolution(
                    sensor,
                    window,
                    timeZone,
                    resolution,
                    availability);
            case RAW -> throw new IllegalStateException("RAW export must be rejected by the query service");
        };
    }

    private StatisticsMaterializedExport materializeSummaryExportAutomatically(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsAvailabilitySnapshot availability
    ) {
        long hourlyRowCount = countHourlyBuckets(window.evaluated());

        if (resolutionPolicy.fitsCsvExportRowLimit(hourlyRowCount)) {
            List<StatisticsDataPoint> hourlyRows =
                    buildHourlyPoints(sensor,window,availability);

            requireExpectedExportRowCount(hourlyRows,hourlyRowCount);

            boolean hourlyTierCoversRange =
                    !containsStatus(hourlyRows,StatisticsPointStatus.EXPIRED)
                            && !containsStatus(hourlyRows,StatisticsPointStatus.ROLLUP_DELAY);

            if (hourlyTierCoversRange) {
                return new StatisticsMaterializedExport(
                        StatisticsResolution.HOURLY,
                        hourlyRows);
            }
        }

        return materializeSummaryExportAtResolution(
                sensor,
                window,
                timeZone,
                StatisticsResolution.DAILY,
                availability);
    }

    private StatisticsMaterializedExport materializeSummaryExportAtResolution(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsResolution resolution,
            StatisticsAvailabilitySnapshot availability
    ) {
        long expectedRowCount = switch (resolution) {
            case HOURLY -> countHourlyBuckets(window.evaluated());
            case DAILY -> countDailyBuckets(window,timeZone);
            case AUTO,RAW -> throw new IllegalStateException(
                    "Summary export requires HOURLY or DAILY resolution");
        };

        resolutionPolicy.requireCsvExportRowLimit(resolution,expectedRowCount);

        List<StatisticsDataPoint> rows = switch (resolution) {
            case HOURLY -> buildHourlyPoints(sensor,window,availability);
            case DAILY -> buildDailyPoints(sensor,window,timeZone,availability);
            case AUTO,RAW -> throw new IllegalStateException(
                    "Summary export requires HOURLY or DAILY resolution");
        };

        requireExpectedExportRowCount(rows,expectedRowCount);

        return new StatisticsMaterializedExport(resolution,rows);
    }

    private void requireExpectedExportRowCount(
            List<StatisticsDataPoint> rows,
            long expectedRowCount
    ) {
        if (rows.size() != expectedRowCount) {
            throw new IllegalStateException(
                    "Materialized summary export row count differs from its projected row count");
        }
    }





    private StatisticsMaterializedSeries materializeAutomatically(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsAvailabilitySnapshot availability,
            RawRangeAvailability rawAvailability
    ) {

        if (rawAvailability == RawRangeAvailability.FULL
                && resolutionPolicy.shouldTryRawAutomatically(
                window.evaluated().duration())) {

            RawFetch rawFetch = fetchRawReadings(
                    sensor,
                    window,
                    availability.history(),
                    availability.raw());

            if (!rawFetch.exceedsPointBudget()) {
                return rawResult(sensor,rawFetch,rawAvailability);
            }
        }

        long hourlyPointCount = countHourlyBuckets(window.evaluated());

        if (resolutionPolicy.fitsPointBudget(hourlyPointCount)) {

            List<StatisticsDataPoint> hourlyPoints =
                    buildHourlyPoints(sensor,window,availability);

            boolean hourlyTierCoversRange =
                    !containsStatus(hourlyPoints,StatisticsPointStatus.EXPIRED)
                            && !containsStatus(hourlyPoints,StatisticsPointStatus.ROLLUP_DELAY);

            if (hourlyTierCoversRange) {
                return new StatisticsMaterializedSeries(
                        StatisticsResolution.HOURLY,
                        rawAvailability,
                        hourlyPoints);
            }
        }

        return materializeDaily(
                sensor,
                window,
                timeZone,
                availability,
                rawAvailability);
    }




    private StatisticsMaterializedSeries materializeRaw(
            Sensor sensor,
            StatisticsQueryWindow window,
            StatisticsAvailabilitySnapshot availability,
            RawRangeAvailability rawAvailability
    ) {

        RawFetch rawFetch = fetchRawReadings(
                sensor,
                window,
                availability.history(),
                availability.raw());

        if (rawFetch.exceedsPointBudget()) {
            resolutionPolicy.requirePointBudget(
                    StatisticsResolution.RAW,
                    queryPolicy.getChartPointBudget() + 1L);
        }

        return rawResult(sensor,rawFetch,rawAvailability);
    }




    private StatisticsMaterializedSeries rawResult(
            Sensor sensor,
            RawFetch rawFetch,
            RawRangeAvailability rawAvailability
    ) {

        List<StatisticsDataPoint> points = new ArrayList<>(rawFetch.readings().size());

        for (SensorReading reading : rawFetch.readings()) {
            points.add(new RawStatisticsDataPoint(
                    reading.getId(),
                    reading.getRecordedAt(),
                    aggregationPolicy.fromReading(sensor.getType(),reading)));
        }

        return new StatisticsMaterializedSeries(
                StatisticsResolution.RAW,
                rawAvailability,
                points);
    }



    private StatisticsMaterializedSeries materializeHourly(
            Sensor sensor,
            StatisticsQueryWindow window,
            StatisticsAvailabilitySnapshot availability,
            RawRangeAvailability rawAvailability
    ) {

        resolutionPolicy.requirePointBudget(
                    StatisticsResolution.HOURLY,
                    countHourlyBuckets(window.evaluated()));

        return new StatisticsMaterializedSeries(
                StatisticsResolution.HOURLY,
                rawAvailability,
                buildHourlyPoints(sensor,window,availability));
    }




    private StatisticsMaterializedSeries materializeDaily(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsAvailabilitySnapshot availability,
            RawRangeAvailability rawAvailability
    ) {

        return new StatisticsMaterializedSeries(
                StatisticsResolution.DAILY,
                rawAvailability,
                buildDailyPoints(sensor,window,timeZone,availability));
    }



    private RawFetch fetchRawReadings(
            Sensor sensor,
            StatisticsQueryWindow window,
            SensorHistory history,
            StatisticsTierAvailability rawAvailability
    ) {

        if (!history.hasReadings()) {
            return new RawFetch(List.of(),false);
        }

        InstantRange retainedRawCoverage = rawAvailability
                .representedCoverage()
                .orElseThrow(() -> new IllegalStateException("Raw tier has no retained coverage"));

        Instant queryStart = laterOf(
                window.evaluated().startInclusive(),
                retainedRawCoverage.startInclusive());

        queryStart = laterOf(
                queryStart,
                history.firstReadingAt().orElseThrow());

        if (!queryStart.isBefore(window.evaluated().endExclusive())) {
            return new RawFetch(List.of(),false);
        }

        Slice<SensorReading> slice = sensorReadingRepository.findForStatisticsRange(
                sensor.getId(),
                queryStart,
                window.evaluated().endExclusive(),
                PageRequest.of(0,queryPolicy.getChartPointBudget()));

        return new RawFetch(slice.getContent(),slice.hasNext());
    }




    private List<StatisticsDataPoint> buildHourlyPoints(
            Sensor sensor,
            StatisticsQueryWindow window,
            StatisticsAvailabilitySnapshot availability
    ) {

        StatisticsTierAvailability hourlyAvailability = availability.hourly();
        InstantRange evaluated = window.evaluated();

        List<HourlySensorSummary> summaries =
                hourlySensorSummaryRepository.findForStatisticsRange(
                        sensor.getId(),
                        hourlyAvailability.retention().expirationCutoff(),
                        evaluated.startInclusive(),
                        evaluated.endExclusive());

        Map<Instant,HourlySensorSummary> summariesByStart = new HashMap<>();

        for (HourlySensorSummary summary : summaries) {
            summariesByStart.put(summary.getBucketStart(),summary);
        }

        List<StatisticsDataPoint> points = new ArrayList<>();
        Instant bucketStart = evaluated.startInclusive().truncatedTo(ChronoUnit.HOURS);
        Instant rollupDueUntil = hourlyAvailability
                .requireRollupProgress()
                .rollupDueUntilExclusive();

        while (bucketStart.isBefore(evaluated.endExclusive())) {
            Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);
            InstantRange bucket = new InstantRange(bucketStart,bucketEnd);
            InstantRange segment = new InstantRange(
                    laterOf(bucketStart,evaluated.startInclusive()),
                    earlierOf(bucketEnd,evaluated.endExclusive()));

            boolean wholeBucket = segment.equals(bucket);
            HourlySensorSummary summary = summariesByStart.get(bucketStart);

            if (wholeBucket && summary != null && hourlyAvailability.verifies(bucket)) {
                points.add(fromHourlySummary(summary));
            } else if (!wholeBucket || bucketEnd.isAfter(rollupDueUntil)) {
                points.add(resolveRawBackedHourlyInterval(
                        sensor,
                        segment,
                        bucket,
                        bucketEnd.isAfter(rollupDueUntil),
                        availability));
            } else {
                points.add(resolveMissingClosedInterval(
                        sensor,
                        segment,
                        bucket,
                        null,
                        null,
                        null,
                        hourlyAvailability,
                        availability.history(),
                        "hourly"));
            }

            bucketStart = bucketEnd;
        }

        return List.copyOf(points);
    }




    private List<StatisticsDataPoint> buildDailyPoints(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsAvailabilitySnapshot availability
    ) {

        StatisticsTierAvailability dailyAvailability = availability.daily();
        InstantRange evaluated = window.evaluated();

        List<DailySensorSummary> summaries =
                dailySensorSummaryRepository.findForStatisticsRange(
                        sensor.getId(),
                        timeZone.getId(),
                        dailyAvailability.retention().expirationCutoff(),
                        evaluated.startInclusive(),
                        evaluated.endExclusive());

        Map<LocalDate,DailySensorSummary> summariesByDate = new HashMap<>();

        for (DailySensorSummary summary : summaries) {
            summariesByDate.put(summary.getLocalDate(),summary);
        }

        LocalDate firstDate = window.firstLocalDate(timeZone);
        LocalDate lastDate = window.lastLocalDate(timeZone);
        Instant rollupDueUntil = dailyAvailability
                .requireRollupProgress()
                .rollupDueUntilExclusive();

        List<StatisticsDataPoint> points = new ArrayList<>();
        LocalDate date = firstDate;

        while (!date.isAfter(lastDate)) {
            Instant bucketStart = date.atStartOfDay(timeZone).toInstant();
            Instant bucketEnd = date.plusDays(1).atStartOfDay(timeZone).toInstant();
            InstantRange bucket = new InstantRange(bucketStart,bucketEnd);
            InstantRange segment = new InstantRange(
                    laterOf(bucketStart,evaluated.startInclusive()),
                    earlierOf(bucketEnd,evaluated.endExclusive()));

            boolean wholeBucket = segment.equals(bucket);
            DailySensorSummary summary = summariesByDate.get(date);

            if (wholeBucket && summary != null && dailyAvailability.verifies(bucket)) {
                points.add(fromDailySummary(summary));
            } else if (!wholeBucket || bucketEnd.isAfter(rollupDueUntil)) {
                points.add(resolveDailyDerivedInterval(
                        sensor,
                        segment,
                        date,
                        timeZone,
                        bucketEnd.isAfter(rollupDueUntil),
                        availability));
            } else {
                points.add(resolveMissingClosedInterval(
                        sensor,
                        segment,
                        bucket,
                        date,
                        date.plusDays(1),
                        timeZone.getId(),
                        dailyAvailability,
                        availability.history(),
                        "daily"));
            }

            date = date.plusDays(1);
        }

        return List.copyOf(points);
    }




    private IntervalStatisticsDataPoint resolveRawBackedHourlyInterval(
            Sensor sensor,
            InstantRange segment,
            InstantRange sourceHour,
            boolean provisional,
            StatisticsAvailabilitySnapshot availability
    ) {

        SourcePart source = resolveRawSourcePart(
                sensor,
                segment,
                sourceHour,
                availability.history(),
                availability.raw(),
                availability.hourly());

        if (source.unavailable()) {
            return unavailableInterval(
                    segment,
                    null,
                    null,
                    null,
                    source.status());
        }

        StatisticsPointStatus status = provisional
                ? StatisticsPointStatus.PARTIAL
                : pointStatusForAggregate(source.aggregate());

        return new IntervalStatisticsDataPoint(
                segment,
                null,
                null,
                null,
                status,
                source.aggregate(),
                null,
                null);
    }




    private IntervalStatisticsDataPoint resolveDailyDerivedInterval(
            Sensor sensor,
            InstantRange segment,
            LocalDate localDate,
            ZoneId timeZone,
            boolean provisional,
            StatisticsAvailabilitySnapshot availability
    ) {

        List<SourcePart> parts = new ArrayList<>();
        Instant completeHoursStart = ceilToHour(segment.startInclusive());
        Instant completeHoursEnd = segment.endExclusive().truncatedTo(ChronoUnit.HOURS);

        if (completeHoursStart.isAfter(completeHoursEnd)) {
            parts.add(resolveRawSourcePart(
                    sensor,
                    segment,
                    containingUtcHour(segment),
                    availability.history(),
                    availability.raw(),
                    availability.hourly()));
        } else {
            if (segment.startInclusive().isBefore(completeHoursStart)) {
                InstantRange leadingFragment = new InstantRange(
                        segment.startInclusive(),
                        completeHoursStart);

                parts.add(resolveRawSourcePart(
                        sensor,
                        leadingFragment,
                        containingUtcHour(leadingFragment),
                        availability.history(),
                        availability.raw(),
                        availability.hourly()));
            }

            Map<Instant,HourlySensorSummary> summariesByStart = loadHourlySummaries(
                    sensor.getId(),
                    completeHoursStart,
                    completeHoursEnd,
                    availability.hourly());

            Instant hourStart = completeHoursStart;

            while (hourStart.isBefore(completeHoursEnd)) {
                InstantRange hour = new InstantRange(
                        hourStart,
                        hourStart.plus(1,ChronoUnit.HOURS));

                parts.add(resolveDailyFullHourSourcePart(
                        sensor,
                        hour,
                        summariesByStart.get(hourStart),
                        availability));

                hourStart = hour.endExclusive();
            }

            if (completeHoursEnd.isBefore(segment.endExclusive())) {
                InstantRange trailingFragment = new InstantRange(
                        completeHoursEnd,
                        segment.endExclusive());

                parts.add(resolveRawSourcePart(
                        sensor,
                        trailingFragment,
                        containingUtcHour(trailingFragment),
                        availability.history(),
                        availability.raw(),
                        availability.hourly()));
            }
        }

        StatisticsPointStatus unavailableStatus = unavailableStatus(parts);

        if (unavailableStatus != null) {
            return unavailableInterval(
                    segment,
                    localDate,
                    localDate.plusDays(1),
                    timeZone.getId(),
                    unavailableStatus);
        }

        SensorSummaryAggregate aggregate = aggregationPolicy.combine(
                sensor.getType(),
                parts.stream().map(SourcePart::aggregate).toList());

        StatisticsPointStatus status = provisional
                ? StatisticsPointStatus.PARTIAL
                : pointStatusForAggregate(aggregate);

        return new IntervalStatisticsDataPoint(
                segment,
                localDate,
                localDate.plusDays(1),
                timeZone.getId(),
                status,
                aggregate,
                null,
                null);
    }




    private SourcePart resolveDailyFullHourSourcePart(
            Sensor sensor,
            InstantRange hour,
            HourlySensorSummary summary,
            StatisticsAvailabilitySnapshot availability
    ) {

        SensorHistory history = availability.history();
        StatisticsTierAvailability hourlyAvailability = availability.hourly();

        if (history.isKnownEmptyUntil(hour.endExclusive())) {
            return SourcePart.available(aggregationPolicy.empty(sensor.getType()));
        }

        if (summary != null && hourlyAvailability.verifies(hour)) {
            return SourcePart.available(summary.toAggregate());
        }

        if (hourlyAvailability.verifies(hour)) {

            if (!hour.endExclusive().isAfter(
                    hourlyAvailability.retention().expirationCutoff())) {

                return SourcePart.unavailable(StatisticsPointStatus.EXPIRED);
            }

            throw new IllegalStateException("Verified hourly coverage is missing a retained summary row");
        }

        return resolveRawSourcePart(
                sensor,
                hour,
                hour,
                history,
                availability.raw(),
                hourlyAvailability);
    }






    private Map<Instant,HourlySensorSummary> loadHourlySummaries(
            Long sensorId,
            Instant startInclusive,
            Instant endExclusive,
            StatisticsTierAvailability hourlyAvailability
    ) {

        if (!startInclusive.isBefore(endExclusive)) {
            return Map.of();
        }

        List<HourlySensorSummary> summaries =
                hourlySensorSummaryRepository.findForStatisticsRange(
                        sensorId,
                        hourlyAvailability.retention().expirationCutoff(),
                        startInclusive,
                        endExclusive);

        Map<Instant,HourlySensorSummary> summariesByStart = new HashMap<>();

        for (HourlySensorSummary summary : summaries) {
            summariesByStart.put(summary.getBucketStart(),summary);
        }

        return summariesByStart;
    }





    private SourcePart resolveRawSourcePart(
            Sensor sensor,
            InstantRange requestedSource,
            InstantRange representedBy,
            SensorHistory history,
            StatisticsTierAvailability rawAvailability,
            StatisticsTierAvailability representationAvailability
    ) {

        if (history.isKnownEmptyUntil(requestedSource.endExclusive())) {
            return SourcePart.available(aggregationPolicy.empty(sensor.getType()));
        }

        Instant dataStart = history
                .firstDataAtOrAfter(requestedSource.startInclusive())
                .orElseThrow();

        InstantRange dataRange = new InstantRange(
                dataStart,
                requestedSource.endExclusive());

        boolean rawCoversSource = rawAvailability
                .representedCoverage()
                .map(retained -> retained.covers(dataRange))
                .orElse(false);

        if (rawCoversSource) {
            SensorSummaryAggregate aggregate = aggregationPolicy.fromRawReadings(
                    sensor.getType(),
                    sensorReadingRepository.aggregateForSummaryRange(
                            sensor.getId(),
                            dataRange.startInclusive(),
                            dataRange.endExclusive()));

            return SourcePart.available(aggregate);
        }

        StatisticsPointStatus status = representationAvailability.verifies(representedBy)
                ? StatisticsPointStatus.EXPIRED
                : StatisticsPointStatus.ROLLUP_DELAY;

        return SourcePart.unavailable(status);
    }





    private IntervalStatisticsDataPoint resolveMissingClosedInterval(
            Sensor sensor,
            InstantRange segment,
            InstantRange sourceBucket,
            LocalDate localDateStart,
            LocalDate localDateEndExclusive,
            String timeZoneId,
            StatisticsTierAvailability tierAvailability,
            SensorHistory history,
            String tierName
    ) {

        if (history.isKnownEmptyUntil(segment.endExclusive())) {
            return new IntervalStatisticsDataPoint(
                    segment,
                    localDateStart,
                    localDateEndExclusive,
                    timeZoneId,
                    StatisticsPointStatus.NO_SAMPLES,
                    aggregationPolicy.empty(sensor.getType()),
                    null,
                    null);
        }

        if (!tierAvailability.verifies(sourceBucket)) {
            return unavailableInterval(
                    segment,
                    localDateStart,
                    localDateEndExclusive,
                    timeZoneId,
                    StatisticsPointStatus.ROLLUP_DELAY);
        }

        if (!segment.endExclusive().isAfter(
                tierAvailability.retention().expirationCutoff())) {

            return unavailableInterval(
                    segment,
                    localDateStart,
                    localDateEndExclusive,
                    timeZoneId,
                    StatisticsPointStatus.EXPIRED);
        }

        throw new IllegalStateException("Verified " + tierName + " coverage is missing a retained summary row");
    }





    private RawRangeAvailability determineRawRangeAvailability(
            StatisticsQueryWindow window,
            SensorHistory history,
            StatisticsTierAvailability rawAvailability
    ) {

        if (!history.hasReadings()) {
            return RawRangeAvailability.FULL;
        }

        Instant dataBearingStart = laterOf(
                window.evaluated().startInclusive(),
                history.firstReadingAt().orElseThrow());

        if (!dataBearingStart.isBefore(window.evaluated().endExclusive())) {
            return RawRangeAvailability.FULL;
        }

        Instant retainedFrom = rawAvailability
                .representedCoverage()
                .orElseThrow()
                .startInclusive();

        if (!dataBearingStart.isBefore(retainedFrom)) {
            return RawRangeAvailability.FULL;
        }

        if (!window.evaluated().endExclusive().isAfter(retainedFrom)) {
            return RawRangeAvailability.EXPIRED;
        }

        return RawRangeAvailability.PARTIAL;
    }




    private IntervalStatisticsDataPoint fromHourlySummary(HourlySensorSummary summary) {

        SensorSummaryAggregate aggregate = summary.toAggregate();

        return new IntervalStatisticsDataPoint(
                new InstantRange(summary.getBucketStart(),summary.getBucketEnd()),
                null,
                null,
                null,
                pointStatusForAggregate(aggregate),
                aggregate,
                summary.getFinalizedAt(),
                summary.getRefreshedAt());
    }




    private IntervalStatisticsDataPoint fromDailySummary(DailySensorSummary summary) {
        SensorSummaryAggregate aggregate = summary.toAggregate();

        return new IntervalStatisticsDataPoint(
                new InstantRange(summary.getBucketStart(),summary.getBucketEnd()),
                summary.getLocalDate(),
                summary.getLocalDate().plusDays(1),
                summary.getTimeZoneId(),
                pointStatusForAggregate(aggregate),
                aggregate,
                summary.getFinalizedAt(),
                summary.getRefreshedAt());
    }




    private IntervalStatisticsDataPoint unavailableInterval(
            InstantRange interval,
            LocalDate localDateStart,
            LocalDate localDateEndExclusive,
            String timeZoneId,
            StatisticsPointStatus status
    ) {
        return new IntervalStatisticsDataPoint(
                interval,
                localDateStart,
                localDateEndExclusive,
                timeZoneId,
                status,
                null,
                null,
                null);
    }




    private StatisticsPointStatus unavailableStatus(List<SourcePart> parts) {

        if (parts.stream().anyMatch(
                part -> part.status() == StatisticsPointStatus.EXPIRED)) {

            return StatisticsPointStatus.EXPIRED;
        }

        if (parts.stream().anyMatch(
                part -> part.status() == StatisticsPointStatus.ROLLUP_DELAY)) {

            return StatisticsPointStatus.ROLLUP_DELAY;
        }

        return null;
    }




    private StatisticsPointStatus pointStatusForAggregate(
            SensorSummaryAggregate aggregate
    ) {
        return aggregate.getSourceSampleCount() == 0
                ? StatisticsPointStatus.NO_SAMPLES
                : StatisticsPointStatus.COMPLETE;
    }




    private boolean containsStatus(List<StatisticsDataPoint> points, StatisticsPointStatus status) {

        return points.stream().anyMatch(point -> point.status() == status);
    }




    private long countHourlyBuckets(InstantRange evaluatedRange) {

        Instant firstBucketStart = evaluatedRange
                .startInclusive()
                .truncatedTo(ChronoUnit.HOURS);

        Instant finalBucketEnd = ceilToHour(evaluatedRange.endExclusive());

        return Duration.between(firstBucketStart,finalBucketEnd).toHours();
    }




    private long countDailyBuckets(StatisticsQueryWindow window, ZoneId timeZone) {
        LocalDate firstDate = window.firstLocalDate(timeZone);
        LocalDate lastDate = window.lastLocalDate(timeZone);

        return ChronoUnit.DAYS.between(firstDate,lastDate) + 1;
    }




    private InstantRange containingUtcHour(InstantRange fragment) {

        Instant hourStart = fragment.startInclusive().truncatedTo(ChronoUnit.HOURS);
        Instant hourEnd = ceilToHour(fragment.endExclusive());

        return new InstantRange(hourStart,hourEnd);
    }




    private Instant ceilToHour(Instant value) {

        Instant floor = value.truncatedTo(ChronoUnit.HOURS);
        return value.equals(floor)
                ? value
                : floor.plus(1,ChronoUnit.HOURS);
    }



    private static Instant earlierOf(Instant first,Instant second) {
        return first.isBefore(second) ? first : second;
    }



    private static Instant laterOf(Instant first,Instant second) {
        return first.isAfter(second) ? first : second;
    }



    private record RawFetch( List<SensorReading> readings, boolean exceedsPointBudget) {

        private RawFetch {
            readings = List.copyOf(Objects.requireNonNull(readings,"readings must not be null"));
        }
    }


    private record SourcePart(StatisticsPointStatus status, SensorSummaryAggregate aggregate) {

        private SourcePart {
            Objects.requireNonNull(status,"status must not be null");
        }


        static SourcePart available(SensorSummaryAggregate aggregate) {
            return new SourcePart(
                    StatisticsPointStatus.COMPLETE,
                    Objects.requireNonNull(aggregate,"aggregate must not be null"));
        }


        static SourcePart unavailable(StatisticsPointStatus status) {

            if (status != StatisticsPointStatus.EXPIRED
                    && status != StatisticsPointStatus.ROLLUP_DELAY) {

                throw new IllegalArgumentException("Unavailable source status must be EXPIRED or ROLLUP_DELAY");
            }

            return new SourcePart(status,null);
        }

        boolean unavailable() {
            return aggregate == null;
        }
    }
}