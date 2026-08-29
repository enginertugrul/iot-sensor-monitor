package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SensorStatisticsSeriesDTO(
        StatisticsSensorDTO sensor,
        Instant requestedStartInclusive,
        Instant requestedEndExclusive,
        Instant evaluatedStartInclusive,
        Instant evaluatedEndExclusive,
        Instant asOf,
        StatisticsResolution requestedResolution,
        StatisticsResolution resolvedResolution,
        StatisticsDisplayGranularity displayGranularity,
        StatisticsRangeStatus status,
        StatisticsRangeConditionsDTO conditions,
        boolean fullyCovered,
        int pointBudget,
        StatisticsCsvExportAvailabilityDTO csvExport,
        StatisticsCoverageDTO coverage,
        StatisticsPeriodMetricsDTO periodMetrics,
        List<StatisticsSeriesPointDTO> points
) {

    public SensorStatisticsSeriesDTO {
        Objects.requireNonNull(sensor,"sensor must not be null");
        Objects.requireNonNull(requestedStartInclusive,"requestedStartInclusive must not be null");
        Objects.requireNonNull(requestedEndExclusive,"requestedEndExclusive must not be null");
        Objects.requireNonNull(evaluatedStartInclusive,"evaluatedStartInclusive must not be null");
        Objects.requireNonNull(evaluatedEndExclusive,"evaluatedEndExclusive must not be null");
        Objects.requireNonNull(asOf,"asOf must not be null");
        Objects.requireNonNull(requestedResolution,"requestedResolution must not be null");
        Objects.requireNonNull(resolvedResolution,"resolvedResolution must not be null");
        Objects.requireNonNull(displayGranularity,"displayGranularity must not be null");
        Objects.requireNonNull(status,"status must not be null");
        Objects.requireNonNull(conditions,"conditions must not be null");
        Objects.requireNonNull(csvExport,"csvExport must not be null");
        Objects.requireNonNull(coverage,"coverage must not be null");
        Objects.requireNonNull(periodMetrics,"periodMetrics must not be null");

        if (fullyCovered != conditions.fullyCovered()) {
            throw new IllegalArgumentException("fullyCovered must agree with the range conditions");
        }

        points = List.copyOf(Objects.requireNonNull(points,"points must not be null"));
    }
}