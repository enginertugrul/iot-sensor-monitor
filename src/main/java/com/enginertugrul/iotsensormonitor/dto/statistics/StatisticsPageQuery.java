package com.enginertugrul.iotsensormonitor.dto.statistics;

import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.Instant;
import java.time.LocalDate;

@Getter
@Setter
public class StatisticsPageQuery {

    private Long sensorId;

    private StatisticsRangePreset preset = StatisticsRangePreset.LAST_7_DAYS;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate startDate;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate endDate;

    private StatisticsResolution resolution = StatisticsResolution.AUTO;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant startInclusive;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private Instant endExclusive;
}