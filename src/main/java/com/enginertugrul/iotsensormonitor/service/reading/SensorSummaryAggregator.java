package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;

import java.math.BigDecimal;



public final class SensorSummaryAggregator {


    private SensorSummaryAggregator() {
    }


    public static SensorSummaryAggregate empty(SensorType sensorType) {

        return switch (sensorType.getReadingValueKind()) {

            case NUMERIC ->
                    SensorSummaryAggregate.emptyNumeric(SensorMeasurementPolicy.requireCanonicalUnit(sensorType));

            case BOOLEAN ->
                    SensorSummaryAggregate.emptyBoolean();
        };

    }


    public static SensorSummaryAggregate fromReading(SensorType sensorType,SensorReading reading) {

        return switch (sensorType.getReadingValueKind()) {
            case NUMERIC -> {
                MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensorType);
                double value = reading.getNumericValue();
                yield SensorSummaryAggregate.numeric(1,canonicalUnit,BigDecimal.valueOf(value),value,value);
            }

            case BOOLEAN -> SensorSummaryAggregate.booleanSamples(1,reading.getBooleanValue() ? 1 : 0);
        };
    }




    public static SensorSummaryAggregate fromRawReadings(
            SensorType sensorType,
            RawSensorReadingAggregateProjection rawAggregate
    ) {

        return switch (sensorType.getReadingValueKind()) {
            case NUMERIC -> SensorSummaryAggregate.numeric(
                    rawAggregate.getSourceSampleCount(),
                    SensorMeasurementPolicy.requireCanonicalUnit(sensorType),
                    rawAggregate.getNumericSum(),
                    rawAggregate.getNumericMinimum(),
                    rawAggregate.getNumericMaximum());

            case BOOLEAN -> SensorSummaryAggregate.booleanSamples(
                    rawAggregate.getSourceSampleCount(),
                    rawAggregate.getTrueSampleCount());
        };
    }




    public static SensorSummaryAggregate combine(
            SensorType sensorType,
            Iterable<SensorSummaryAggregate> aggregates
    ) {

        return switch (sensorType.getReadingValueKind()) {
            case NUMERIC -> combineNumeric(sensorType,aggregates);
            case BOOLEAN -> combineBoolean(sensorType,aggregates);
        };
    }




    private static SensorSummaryAggregate combineNumeric(
            SensorType sensorType,
            Iterable<SensorSummaryAggregate> aggregates
    ) {

        MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensorType);
        long sourceSampleCount = 0;
        BigDecimal numericSum = BigDecimal.ZERO;
        Double numericMinimum = null;
        Double numericMaximum = null;

        for (SensorSummaryAggregate aggregate : aggregates) {
            aggregate.requireCompatibleWith(sensorType);
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



    private static SensorSummaryAggregate combineBoolean(
            SensorType sensorType,
            Iterable<SensorSummaryAggregate> aggregates
    ) {

        long sourceSampleCount = 0;
        long trueSampleCount = 0;

        for (SensorSummaryAggregate aggregate : aggregates) {
            aggregate.requireCompatibleWith(sensorType);
            sourceSampleCount = Math.addExact(sourceSampleCount,aggregate.getSourceSampleCount());
            trueSampleCount = Math.addExact(trueSampleCount,aggregate.getTrueSampleCount());
        }

        return SensorSummaryAggregate.booleanSamples(sourceSampleCount,trueSampleCount);
    }

}