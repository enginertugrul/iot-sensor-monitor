package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.math.BigDecimal;

public record StatisticsNumericMetricsDTO(
        BigDecimal sum,
        BigDecimal minimum,
        BigDecimal average,
        BigDecimal maximum
) {
}