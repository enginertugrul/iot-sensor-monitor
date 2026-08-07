package com.enginertugrul.iotsensormonitor.repository;

import java.math.BigDecimal;

public interface RawSensorReadingAggregateProjection {

    long getSourceSampleCount();

    long getNumericSampleCount();

    long getBooleanSampleCount();

    long getUnitSampleCount();

    String getMinimumUnit();

    String getMaximumUnit();

    BigDecimal getNumericSum();

    Double getNumericMinimum();

    Double getNumericMaximum();

    long getTrueSampleCount();
}