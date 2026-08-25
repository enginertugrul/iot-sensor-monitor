package com.enginertugrul.iotsensormonitor.repository;

import java.math.BigDecimal;

public interface RawSensorReadingAggregateProjection {

    long getSourceSampleCount();

    BigDecimal getNumericSum();

    Double getNumericMinimum();

    Double getNumericMaximum();

    long getTrueSampleCount();
}