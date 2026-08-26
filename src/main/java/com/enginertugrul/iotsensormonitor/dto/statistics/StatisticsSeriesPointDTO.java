package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.time.Instant;
import java.time.LocalDate;

public record StatisticsSeriesPointDTO(
        StatisticsDisplayGranularity granularity,
        Long sourceReadingId,
        Instant recordedAt,
        Instant bucketStart,
        Instant bucketEnd,
        LocalDate localDateStart,
        LocalDate localDateEndExclusive,
        String timeZoneId,
        StatisticsPointStatus status,
        Long sourceSampleCount,
        StatisticsNumericMetricsDTO numericMetrics,
        StatisticsMotionMetricsDTO motionMetrics,
        Instant finalizedAt,
        Instant refreshedAt
) {
}