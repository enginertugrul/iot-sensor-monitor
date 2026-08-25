package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Getter
@Component
public class StatisticsQueryPolicy {

    private final int chartPointBudget;
    private final Duration autoRawMaximumRange;
    private final Duration maximumRange;

    public StatisticsQueryPolicy(
            @Value("${app.sensor-data.statistics.chart-point-budget:400}") int chartPointBudget,
            @Value("${app.sensor-data.statistics.auto-raw-maximum-range:PT1H}") Duration autoRawMaximumRange,
            @Value("${app.sensor-data.statistics.maximum-range:P1095D}") Duration maximumRange
    ) {
        this.chartPointBudget = requireInRange(chartPointBudget,1,10_000,"chartPointBudget");
        this.autoRawMaximumRange = requirePositive(autoRawMaximumRange,"autoRawMaximumRange");
        this.maximumRange = requirePositive(maximumRange,"maximumRange");

        if (this.autoRawMaximumRange.compareTo(this.maximumRange) > 0) {
            throw new IllegalArgumentException("autoRawMaximumRange must not exceed maximumRange");
        }
    }

    private static Duration requirePositive(Duration value,String fieldName) {
        Duration requiredValue = Objects.requireNonNull(value,fieldName + " must not be null");

        if (requiredValue.isZero() || requiredValue.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }

        return requiredValue;
    }

    private static int requireInRange(int value,int minimum,int maximum,String fieldName) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(fieldName + " must be between " + minimum + " and " + maximum);
        }

        return value;
    }
}