package com.enginertugrul.iotsensormonitor.dto.statistics;

public record StatisticsCsvExportAvailabilityDTO(
        boolean available,
        int rowCount,
        int rowLimit
) {
}