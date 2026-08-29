package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.time.Instant;
import java.util.List;

public record SensorStatisticsExportDTO(
        StatisticsSensorDTO sensor,
        Instant evaluatedStartInclusive,
        Instant evaluatedEndExclusive,
        StatisticsResolution resolvedResolution,
        List<StatisticsSeriesPointDTO> rows
) {

    public SensorStatisticsExportDTO {
        rows = List.copyOf(rows);
    }
}