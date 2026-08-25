package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.math.BigDecimal;

public record StatisticsMotionMetricsDTO(
        long totalSampleCount,
        long trueSampleCount,
        long falseSampleCount,
        BigDecimal truePercentage
) {
}