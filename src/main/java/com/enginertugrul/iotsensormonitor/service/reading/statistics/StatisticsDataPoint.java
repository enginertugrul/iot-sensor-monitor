package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

sealed interface StatisticsDataPoint permits RawStatisticsDataPoint,IntervalStatisticsDataPoint {

    StatisticsPointStatus status();

    SensorSummaryAggregate aggregate();
}


record RawStatisticsDataPoint(
        Long sourceReadingId,
        Instant recordedAt,
        SensorSummaryAggregate aggregate
) implements StatisticsDataPoint {

    RawStatisticsDataPoint {
        Objects.requireNonNull(sourceReadingId,"sourceReadingId must not be null");
        Objects.requireNonNull(recordedAt,"recordedAt must not be null");
        Objects.requireNonNull(aggregate,"aggregate must not be null");
    }

    @Override
    public StatisticsPointStatus status() {
        return StatisticsPointStatus.COMPLETE;
    }
}


record IntervalStatisticsDataPoint(
        InstantRange interval,
        LocalDate localDateStart,
        LocalDate localDateEndExclusive,
        String timeZoneId,
        StatisticsPointStatus status,
        SensorSummaryAggregate aggregate,
        Instant finalizedAt,
        Instant refreshedAt
) implements StatisticsDataPoint {

    IntervalStatisticsDataPoint {
        Objects.requireNonNull(interval,"interval must not be null");
        Objects.requireNonNull(status,"status must not be null");

        if (interval.isEmpty()) {
            throw new IllegalArgumentException("A statistics interval must not be empty");
        }

        boolean hasLocalDateStart = localDateStart != null;
        boolean hasLocalDateEnd = localDateEndExclusive != null;
        boolean hasTimeZone = timeZoneId != null;

        if (!(hasLocalDateStart == hasLocalDateEnd
                && hasLocalDateEnd == hasTimeZone)) {

            throw new IllegalArgumentException("Local-date and timezone fields must be populated together");
        }


        if (hasLocalDateStart && !localDateStart.isBefore(localDateEndExclusive)) {
            throw new IllegalArgumentException("localDateStart must be before localDateEndExclusive");
        }


        boolean unavailable = status == StatisticsPointStatus.EXPIRED
                || status == StatisticsPointStatus.ROLLUP_DELAY;

        if (unavailable != (aggregate == null)) {
            throw new IllegalArgumentException("Only unavailable points may omit their aggregate");
        }

        if ((finalizedAt == null) != (refreshedAt == null)) {
            throw new IllegalArgumentException("finalizedAt and refreshedAt must be populated together");
        }
    }
}


enum RawRangeAvailability {

    FULL,
    PARTIAL,
    EXPIRED
}


record StatisticsMaterializedSeries(
        StatisticsResolution resolvedResolution,
        RawRangeAvailability rawRangeAvailability,
        List<StatisticsDataPoint> sourcePoints
) {

    StatisticsMaterializedSeries {
        Objects.requireNonNull(resolvedResolution,"resolvedResolution must not be null");
        Objects.requireNonNull(rawRangeAvailability, "rawRangeAvailability must not be null");

        if (resolvedResolution == StatisticsResolution.AUTO) {
            throw new IllegalArgumentException("AUTO is not a resolved resolution");
        }

        sourcePoints = List.copyOf(Objects.requireNonNull(sourcePoints,"sourcePoints must not be null"));
    }
}



record StatisticsMaterializedExport(
        StatisticsResolution resolvedResolution,
        List<StatisticsDataPoint> rows
) {

    StatisticsMaterializedExport {
        rows = List.copyOf(rows);
    }
}