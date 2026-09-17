package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
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
    private final StatisticsIntervalResolver intervalResolver;



    public StatisticsSeriesMaterializer(SensorReadingRepository sensorReadingRepository,HourlySensorSummaryRepository hourlySensorSummaryRepository,DailySensorSummaryRepository dailySensorSummaryRepository,StatisticsQueryPolicy queryPolicy,StatisticsResolutionPolicy resolutionPolicy,StatisticsIntervalResolver intervalResolver) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
        this.dailySensorSummaryRepository = dailySensorSummaryRepository;
        this.queryPolicy = queryPolicy;
        this.resolutionPolicy = resolutionPolicy;
        this.intervalResolver = intervalResolver;
    }




    StatisticsMaterializedSeries materialize(
            Sensor sensor,
            StatisticsQueryWindow window,
            ZoneId timeZone,
            StatisticsResolution requestedResolution,
            StatisticsAvailabilitySnapshot availability
    ) {

        RawRangeAvailability rawAvailability = intervalResolver.determineRawRangeAvailability(window, availability.history(), availability.raw());

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

            List<StatisticsDataPoint> hourlyRows = buildHourlyPoints(sensor,window,availability);

            requireExpectedExportRowCount(hourlyRows,hourlyRowCount);

            boolean hourlyTierCoversRange =
                    !containsStatus(hourlyRows,StatisticsPointStatus.EXPIRED)
                            && !containsStatus(hourlyRows,StatisticsPointStatus.ROLLUP_DELAY);

            if (hourlyTierCoversRange) {
                return new StatisticsMaterializedExport(
                        StatisticsResolution.HOURLY, hourlyRows);
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



    private void requireExpectedExportRowCount(List<StatisticsDataPoint> rows, long expectedRowCount) {

        if (rows.size() != expectedRowCount) {
            throw new IllegalStateException("Materialized summary export row count differs from its projected row count");
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

            List<StatisticsDataPoint> hourlyPoints = buildHourlyPoints(sensor,window,availability);

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
                    SensorSummaryAggregator.fromReading(sensor.getType(),reading)));
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




    private List<StatisticsDataPoint> buildHourlyPoints(Sensor sensor,StatisticsQueryWindow window,StatisticsAvailabilitySnapshot availability) {

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

        while (bucketStart.isBefore(evaluated.endExclusive())) {
            Instant bucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);
            InstantRange bucket = new InstantRange(bucketStart,bucketEnd);
            InstantRange segment = new InstantRange(
                    laterOf(bucketStart, evaluated.startInclusive()),
                    earlierOf(bucketEnd, evaluated.endExclusive()));

            points.add(intervalResolver.resolveHourlyInterval(sensor, segment, bucket, summariesByStart.get(bucketStart), availability));
            bucketStart = bucketEnd;
        }

        return List.copyOf(points);
    }



    private List<StatisticsDataPoint> buildDailyPoints(Sensor sensor,StatisticsQueryWindow window,ZoneId timeZone,StatisticsAvailabilitySnapshot availability) {

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
        List<StatisticsDataPoint> points = new ArrayList<>();
        LocalDate date = firstDate;

        while (!date.isAfter(lastDate)) {

            Instant bucketStart = date.atStartOfDay(timeZone).toInstant();
            Instant bucketEnd = date.plusDays(1).atStartOfDay(timeZone).toInstant();

            InstantRange bucket = new InstantRange(bucketStart,bucketEnd);
            InstantRange segment = new InstantRange(
                    laterOf(bucketStart,evaluated.startInclusive()),
                    earlierOf(bucketEnd,evaluated.endExclusive()));

            points.add(intervalResolver.resolveDailyInterval(sensor,segment,bucket,date,timeZone,summariesByDate.get(date),availability));
            date = date.plusDays(1);
        }

        return List.copyOf(points);
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


}