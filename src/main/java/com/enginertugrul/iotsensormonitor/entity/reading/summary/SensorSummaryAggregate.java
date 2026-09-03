package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.ReadingValueKind;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Objects;

@Getter
public final class SensorSummaryAggregate {

    private final ReadingValueKind readingValueKind;
    private final long sourceSampleCount;
    private final MeasurementUnit unit;
    private final BigDecimal numericSum;
    private final Double numericMinimum;
    private final Double numericMaximum;
    private final Long trueSampleCount;

    private SensorSummaryAggregate(ReadingValueKind readingValueKind, long sourceSampleCount, MeasurementUnit unit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum, Long trueSampleCount) {

        this.readingValueKind = Objects.requireNonNull(readingValueKind,"readingValueKind must not be null");
        this.sourceSampleCount = sourceSampleCount;
        this.unit = unit;
        this.numericSum = numericSum;
        this.numericMinimum = numericMinimum;
        this.numericMaximum = numericMaximum;
        this.trueSampleCount = trueSampleCount;
    }




    public static SensorSummaryAggregate emptyNumeric(MeasurementUnit canonicalUnit) {
        return numeric(0,canonicalUnit,null,null,null);
    }




    public static SensorSummaryAggregate numeric(long sourceSampleCount, MeasurementUnit canonicalUnit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum) {

        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                ReadingValueKind.NUMERIC,
                sourceSampleCount,
                canonicalUnit,
                numericSum,
                numericMinimum,
                numericMaximum,
                null);

        aggregate.validateShape();

        return aggregate;
    }




    public static SensorSummaryAggregate emptyBoolean() {
        return booleanSamples(0,0);
    }




    public static SensorSummaryAggregate booleanSamples(long sourceSampleCount, long trueSampleCount) {
        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                ReadingValueKind.BOOLEAN,
                sourceSampleCount,
                null,
                null,
                null,
                null,
                trueSampleCount);

        aggregate.validateShape();

        return aggregate;
    }




    static SensorSummaryAggregate restore(ReadingValueKind readingValueKind, long sourceSampleCount, MeasurementUnit unit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum, Long trueSampleCount) {

        return new SensorSummaryAggregate(
                readingValueKind,
                sourceSampleCount,
                unit,
                numericSum,
                numericMinimum,
                numericMaximum,
                trueSampleCount);
    }




    public boolean isNumeric() {
        return readingValueKind == ReadingValueKind.NUMERIC;
    }




    public boolean isBoolean() {
        return readingValueKind == ReadingValueKind.BOOLEAN;
    }




    public void requireCompatibleWith(SensorType sensorType) {

        Objects.requireNonNull(sensorType,"sensorType must not be null");

        if (readingValueKind != sensorType.getReadingValueKind()) {
            throw new IllegalArgumentException("Summary aggregate value kind does not match sensor type " + sensorType);
        }

        if (isNumeric() && unit != SensorMeasurementPolicy.requireCanonicalUnit(sensorType)) {
            throw new IllegalArgumentException("Summary aggregate unit does not match sensor type " + sensorType);
        }
    }




    private void validateShape() {

        if (sourceSampleCount < 0) {
            throw new IllegalArgumentException("sourceSampleCount must not be negative");
        }

        switch (readingValueKind) {
            case NUMERIC -> validateNumericShape();
            case BOOLEAN -> validateBooleanShape();
        }
    }




    private void validateNumericShape() {

        Objects.requireNonNull(unit,"unit must not be null for numeric summaries");

        if (trueSampleCount != null) {
            throw new IllegalArgumentException("Numeric summaries must not contain trueSampleCount");
        }

        if (sourceSampleCount == 0) {
            if (numericSum != null || numericMinimum != null || numericMaximum != null) {
                throw new IllegalArgumentException("Empty numeric summaries must not contain numeric aggregate values");
            }

            return;
        }

        Objects.requireNonNull(numericSum,"numericSum must not be null when sourceSampleCount is positive");
        double validMinimum = DomainChecks.requireFiniteDouble(numericMinimum,"numericMinimum");
        double validMaximum = DomainChecks.requireFiniteDouble(numericMaximum,"numericMaximum");

        if (validMinimum > validMaximum) {
            throw new IllegalArgumentException("numericMinimum must not exceed numericMaximum");
        }
    }




    private void validateBooleanShape() {

        if (unit != null || numericSum != null || numericMinimum != null || numericMaximum != null) {
            throw new IllegalArgumentException("Boolean summaries must not contain numeric aggregate values or a measurement unit");
        }

        if (trueSampleCount == null) {
            throw new IllegalArgumentException("Boolean summaries must contain trueSampleCount");
        }

        if (trueSampleCount < 0 || trueSampleCount > sourceSampleCount) {
            throw new IllegalArgumentException("trueSampleCount must be between zero and sourceSampleCount");
        }
    }

}