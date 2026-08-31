package com.enginertugrul.iotsensormonitor.dto.statistics;

public record StatisticsPeriodMetricsDTO(
        boolean available,
        long sourceSampleCount,
        StatisticsNumericMetricsDTO numericMetrics,
        StatisticsMotionMetricsDTO motionMetrics
) {
}