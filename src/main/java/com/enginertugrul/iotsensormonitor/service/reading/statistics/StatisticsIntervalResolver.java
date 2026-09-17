package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.service.reading.SensorSummaryAggregator;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;





@Component
public class StatisticsIntervalResolver {


    private final SensorReadingRepository sensorReadingRepository;
    private final HourlySensorSummaryRepository hourlySensorSummaryRepository;


    public StatisticsIntervalResolver(SensorReadingRepository sensorReadingRepository,HourlySensorSummaryRepository hourlySensorSummaryRepository) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.hourlySensorSummaryRepository = hourlySensorSummaryRepository;
    }




    RawRangeAvailability determineRawRangeAvailability(StatisticsQueryWindow window,SensorHistory history,StatisticsTierAvailability rawAvailability) {
        if (!history.hasReadings()) {
            return RawRangeAvailability.FULL;
        }

        Instant dataBearingStart = laterOf(window.evaluated().startInclusive(),history.firstReadingAt().orElseThrow());

        if (!dataBearingStart.isBefore(window.evaluated().endExclusive())) {
            return RawRangeAvailability.FULL;
        }

        Instant retainedFrom = rawAvailability.representedCoverage()
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





    IntervalStatisticsDataPoint resolveHourlyInterval(Sensor sensor, InstantRange segment, InstantRange bucket, HourlySensorSummary summary, StatisticsAvailabilitySnapshot availability) {

        StatisticsTierAvailability hourlyAvailability = availability.hourly();
        boolean wholeBucket = segment.equals(bucket);
        boolean provisional = bucket.endExclusive().isAfter(hourlyAvailability.requireRollupProgress().rollupDueUntilExclusive());

        if (wholeBucket && summary != null && hourlyAvailability.verifies(bucket)) {
            return fromHourlySummary(summary);
        }

        if (!wholeBucket || provisional) {
            return resolveRawBackedHourlyInterval(sensor,segment,bucket,provisional,availability);
        }

        return resolveMissingClosedInterval(
                sensor, segment, bucket,
                null,null,null,
                hourlyAvailability, availability.history(), "hourly"
        );
    }




    IntervalStatisticsDataPoint resolveDailyInterval(Sensor sensor,InstantRange segment,InstantRange bucket,LocalDate localDate,ZoneId timeZone,DailySensorSummary summary,StatisticsAvailabilitySnapshot availability) {
        StatisticsTierAvailability dailyAvailability = availability.daily();
        boolean wholeBucket = segment.equals(bucket);
        boolean provisional = bucket.endExclusive().isAfter(dailyAvailability.requireRollupProgress().rollupDueUntilExclusive());

        if (wholeBucket && summary != null && dailyAvailability.verifies(bucket)) {
            return fromDailySummary(summary);
        }

        if (!wholeBucket || provisional) {
            return resolveDailyDerivedInterval(sensor,segment,localDate,timeZone,provisional,availability);
        }

        return resolveMissingClosedInterval(sensor,segment,bucket,localDate,localDate.plusDays(1),timeZone.getId(),dailyAvailability,availability.history(),"daily");
    }





    private IntervalStatisticsDataPoint resolveRawBackedHourlyInterval(Sensor sensor,InstantRange segment,InstantRange sourceHour,boolean provisional,StatisticsAvailabilitySnapshot availability) {
        SourcePart source = resolveRawSourcePart(sensor,segment,sourceHour,availability.history(),availability.raw(),availability.hourly());

        if (source.unavailable()) {
            return unavailableInterval(segment,null,null,null,source.status());
        }

        StatisticsPointStatus status = provisional
                ? StatisticsPointStatus.PARTIAL
                : pointStatusForAggregate(source.aggregate());

        return new IntervalStatisticsDataPoint(segment,null,null,null,status,source.aggregate(),null,null);
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
                InstantRange leadingFragment = new InstantRange(segment.startInclusive(), completeHoursStart);

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

                InstantRange hour = new InstantRange(hourStart, hourStart.plus(1,ChronoUnit.HOURS));

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

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.combine(
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







    private SourcePart resolveDailyFullHourSourcePart(Sensor sensor,InstantRange hour,HourlySensorSummary summary,StatisticsAvailabilitySnapshot availability) {

        SensorHistory history = availability.history();
        StatisticsTierAvailability hourlyAvailability = availability.hourly();

        if (history.isKnownEmptyUntil(hour.endExclusive())) {
            return SourcePart.available(SensorSummaryAggregator.empty(sensor.getType()));
        }

        if (summary != null && hourlyAvailability.verifies(hour)) {
            return SourcePart.available(summary.toAggregate());
        }

        if (hourlyAvailability.verifies(hour)) {
            if (!hour.endExclusive().isAfter(hourlyAvailability.retention().expirationCutoff())) {
                return SourcePart.unavailable(StatisticsPointStatus.EXPIRED);
            }

            throw new IllegalStateException("Verified hourly coverage is missing a retained summary row");
        }

        return resolveRawSourcePart(sensor,hour,hour,history,availability.raw(),hourlyAvailability);
    }







    private Map<Instant,HourlySensorSummary> loadHourlySummaries(Long sensorId,Instant startInclusive,Instant endExclusive,StatisticsTierAvailability hourlyAvailability) {

        if (!startInclusive.isBefore(endExclusive)) {
            return Map.of();
        }

        List<HourlySensorSummary> summaries = hourlySensorSummaryRepository.findForStatisticsRange(
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






    private SourcePart resolveRawSourcePart(Sensor sensor,InstantRange requestedSource,InstantRange representedBy,SensorHistory history,StatisticsTierAvailability rawAvailability,StatisticsTierAvailability representationAvailability) {
        if (history.isKnownEmptyUntil(requestedSource.endExclusive())) {
            return SourcePart.available(SensorSummaryAggregator.empty(sensor.getType()));
        }

        Instant dataStart = history.firstDataAtOrAfter(requestedSource.startInclusive()).orElseThrow();
        InstantRange dataRange = new InstantRange(dataStart,requestedSource.endExclusive());

        boolean rawCoversSource = rawAvailability.representedCoverage()
                .map(retained -> retained.covers(dataRange))
                .orElse(false);

        if (rawCoversSource) {
            SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(
                    sensor.getType(),
                    sensorReadingRepository.aggregateForSummaryRange(sensor.getId(),dataRange.startInclusive(),dataRange.endExclusive()));

            return SourcePart.available(aggregate);
        }

        StatisticsPointStatus status = representationAvailability.verifies(representedBy)
                ? StatisticsPointStatus.EXPIRED
                : StatisticsPointStatus.ROLLUP_DELAY;

        return SourcePart.unavailable(status);
    }






    private IntervalStatisticsDataPoint resolveMissingClosedInterval(Sensor sensor,InstantRange segment,InstantRange sourceBucket,LocalDate localDateStart,LocalDate localDateEndExclusive,String timeZoneId,StatisticsTierAvailability tierAvailability,SensorHistory history,String tierName) {
        if (history.isKnownEmptyUntil(segment.endExclusive())) {
            return new IntervalStatisticsDataPoint(
                    segment,localDateStart,localDateEndExclusive,timeZoneId,
                    StatisticsPointStatus.NO_SAMPLES,SensorSummaryAggregator.empty(sensor.getType()),null,null);
        }

        if (!tierAvailability.verifies(sourceBucket)) {
            return unavailableInterval(segment,localDateStart,localDateEndExclusive,timeZoneId,StatisticsPointStatus.ROLLUP_DELAY);
        }

        if (!segment.endExclusive().isAfter(tierAvailability.retention().expirationCutoff())) {
            return unavailableInterval(segment,localDateStart,localDateEndExclusive,timeZoneId,StatisticsPointStatus.EXPIRED);
        }

        throw new IllegalStateException("Verified " + tierName + " coverage is missing a retained summary row");
    }





    private IntervalStatisticsDataPoint fromHourlySummary(HourlySensorSummary summary) {
        SensorSummaryAggregate aggregate = summary.toAggregate();

        return new IntervalStatisticsDataPoint(
                new InstantRange(summary.getBucketStart(),summary.getBucketEnd()),
                null,null,null,pointStatusForAggregate(aggregate),aggregate,
                summary.getFinalizedAt(),summary.getRefreshedAt());
    }





    private IntervalStatisticsDataPoint fromDailySummary(DailySensorSummary summary) {
        SensorSummaryAggregate aggregate = summary.toAggregate();

        return new IntervalStatisticsDataPoint(
                new InstantRange(summary.getBucketStart(),summary.getBucketEnd()),
                summary.getLocalDate(),summary.getLocalDate().plusDays(1),summary.getTimeZoneId(),
                pointStatusForAggregate(aggregate),aggregate,summary.getFinalizedAt(),summary.getRefreshedAt());
    }



    private IntervalStatisticsDataPoint unavailableInterval(InstantRange interval,LocalDate localDateStart,LocalDate localDateEndExclusive,String timeZoneId,StatisticsPointStatus status) {
        return new IntervalStatisticsDataPoint(interval,localDateStart,localDateEndExclusive,timeZoneId,status,null,null,null);
    }





    private StatisticsPointStatus unavailableStatus(List<SourcePart> parts) {
        if (parts.stream().anyMatch(part -> part.status() == StatisticsPointStatus.EXPIRED)) {
            return StatisticsPointStatus.EXPIRED;
        }

        if (parts.stream().anyMatch(part -> part.status() == StatisticsPointStatus.ROLLUP_DELAY)) {
            return StatisticsPointStatus.ROLLUP_DELAY;
        }

        return null;
    }




    private StatisticsPointStatus pointStatusForAggregate(SensorSummaryAggregate aggregate) {
        return aggregate.getSourceSampleCount() == 0
                ? StatisticsPointStatus.NO_SAMPLES
                : StatisticsPointStatus.COMPLETE;
    }




    private InstantRange containingUtcHour(InstantRange fragment) {
        Instant hourStart = fragment.startInclusive().truncatedTo(ChronoUnit.HOURS);
        Instant hourEnd = ceilToHour(fragment.endExclusive());

        return new InstantRange(hourStart,hourEnd);
    }




    private Instant ceilToHour(Instant value) {
        Instant floor = value.truncatedTo(ChronoUnit.HOURS);
        return value.equals(floor) ? value : floor.plus(1,ChronoUnit.HOURS);
    }



    private static Instant laterOf(Instant first,Instant second) {
        return first.isAfter(second) ? first : second;
    }




    private record SourcePart(StatisticsPointStatus status,SensorSummaryAggregate aggregate) {

        private SourcePart {
            Objects.requireNonNull(status,"status must not be null");
        }

        static SourcePart available(SensorSummaryAggregate aggregate) {
            return new SourcePart(StatisticsPointStatus.COMPLETE,Objects.requireNonNull(aggregate,"aggregate must not be null"));
        }

        static SourcePart unavailable(StatisticsPointStatus status) {
            if (status != StatisticsPointStatus.EXPIRED && status != StatisticsPointStatus.ROLLUP_DELAY) {
                throw new IllegalArgumentException("Unavailable source status must be EXPIRED or ROLLUP_DELAY");
            }

            return new SourcePart(status,null);
        }

        boolean unavailable() {
            return aggregate == null;
        }
    }

}