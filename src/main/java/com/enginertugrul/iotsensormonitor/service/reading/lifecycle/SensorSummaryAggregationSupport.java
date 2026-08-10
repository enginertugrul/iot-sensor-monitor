package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;

import java.math.BigDecimal;
import java.util.Objects;

final class SensorSummaryAggregationSupport {

    private SensorSummaryAggregationSupport() {
    }




    static SensorSummaryAggregate fromRawReadings(SensorType sensorType, RawSensorReadingAggregateProjection rawAggregate) {

        SensorType requiredSensorType = Objects.requireNonNull(sensorType,"sensorType must not be null");

        RawSensorReadingAggregateProjection requiredRawAggregate = Objects.requireNonNull(rawAggregate,"rawAggregate must not be null");


        long sourceSampleCount = requiredRawAggregate.getSourceSampleCount();

        return switch (requiredSensorType) {

            case MOTION -> SensorSummaryAggregate.motion(
                    sourceSampleCount,
                    requiredRawAggregate.getTrueSampleCount());

            case TEMPERATURE, HUMIDITY -> {

                MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(requiredSensorType);

                yield SensorSummaryAggregate.numeric(
                    sourceSampleCount,
                    canonicalUnit,
                    requiredRawAggregate.getNumericSum(),
                    requiredRawAggregate.getNumericMinimum(),
                    requiredRawAggregate.getNumericMaximum());
            }
        };
    }





    static SensorSummaryAggregate combine(SensorType sensorType, Iterable<SensorSummaryAggregate> aggregates) {

        SensorType requiredSensorType = Objects.requireNonNull(sensorType,"sensorType must not be null");

        Iterable<SensorSummaryAggregate> requiredAggregates = Objects.requireNonNull(aggregates,"aggregates must not be null");

        return switch (requiredSensorType) {

            case MOTION ->
                    combineMotion(requiredAggregates);

            case TEMPERATURE, HUMIDITY ->
                    combineNumeric(requiredSensorType,requiredAggregates);
        };
    }











    private static SensorSummaryAggregate combineMotion(Iterable<SensorSummaryAggregate> aggregates) {

        long sourceSampleCount = 0;
        long trueSampleCount = 0;

        for (SensorSummaryAggregate candidate : aggregates) {

            SensorSummaryAggregate aggregate = Objects.requireNonNull(candidate,"aggregates must not contain null");

            if (!aggregate.isMotion()) {
                throw new IllegalStateException("Cannot combine numeric and motion summary aggregates");
            }

            sourceSampleCount = Math.addExact(sourceSampleCount,aggregate.getSourceSampleCount());
            trueSampleCount = Math.addExact(trueSampleCount,aggregate.getTrueSampleCount());
        }

        return SensorSummaryAggregate.motion(sourceSampleCount,trueSampleCount);
    }





    private static SensorSummaryAggregate combineNumeric(SensorType sensorType, Iterable<SensorSummaryAggregate> aggregates) {

        MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensorType);

        long sourceSampleCount = 0;
        BigDecimal numericSum = BigDecimal.ZERO;
        Double numericMinimum = null;
        Double numericMaximum = null;

        for (SensorSummaryAggregate candidate : aggregates) {

            SensorSummaryAggregate aggregate = Objects.requireNonNull(candidate,"aggregates must not contain null");

            if (aggregate.getUnit() != canonicalUnit) {
                throw new IllegalStateException("Cannot combine aggregates with incompatible measurement units");
            }

            sourceSampleCount = Math.addExact(sourceSampleCount,aggregate.getSourceSampleCount());

            if (aggregate.getSourceSampleCount() == 0) {
                continue;
            }

            numericSum = numericSum.add(aggregate.getNumericSum());

            numericMinimum = numericMinimum == null
                    ? aggregate.getNumericMinimum()
                    : Math.min(numericMinimum,aggregate.getNumericMinimum());

            numericMaximum = numericMaximum == null
                    ? aggregate.getNumericMaximum()
                    : Math.max(numericMaximum,aggregate.getNumericMaximum());
        }

        if (sourceSampleCount == 0) {
            return SensorSummaryAggregate.emptyNumeric(canonicalUnit);
        }

        return SensorSummaryAggregate.numeric(
                sourceSampleCount,
                canonicalUnit,
                numericSum,
                numericMinimum,
                numericMaximum);
    }




}