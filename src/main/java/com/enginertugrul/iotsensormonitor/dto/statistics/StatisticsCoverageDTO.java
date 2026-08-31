package com.enginertugrul.iotsensormonitor.dto.statistics;

public record StatisticsCoverageDTO(
        StatisticsTierCoverageDTO raw,
        StatisticsTierCoverageDTO hourly,
        StatisticsTierCoverageDTO daily
) {
}