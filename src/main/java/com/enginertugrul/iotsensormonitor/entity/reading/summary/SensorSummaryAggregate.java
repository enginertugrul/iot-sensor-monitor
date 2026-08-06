package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.Objects;

@Getter
public final class SensorSummaryAggregate {

    private final long sourceSampleCount;
    private final MeasurementUnit unit;
    private final BigDecimal numericSum;
    private final Double numericMinimum;
    private final Double numericMaximum;
    private final Long trueSampleCount;

    private SensorSummaryAggregate(long sourceSampleCount, MeasurementUnit unit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum, Long trueSampleCount) {
        this.sourceSampleCount = sourceSampleCount;
        this.unit = unit;
        this.numericSum = numericSum;
        this.numericMinimum = numericMinimum;
        this.numericMaximum = numericMaximum;
        this.trueSampleCount = trueSampleCount;
    }




    public static SensorSummaryAggregate emptyNumeric(SensorType sensorType) {
        return numeric(sensorType,0,null,null,null);
    }




    public static SensorSummaryAggregate numeric(SensorType sensorType, long sourceSampleCount, BigDecimal numericSum, Double numericMinimum, Double numericMaximum) {

        SensorType requiredSensorType = Objects.requireNonNull(sensorType,"sensorType must not be null");
        MeasurementUnit canonicalUnit = SensorMeasurementPolicy.requireCanonicalUnit(requiredSensorType);

        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                sourceSampleCount,
                canonicalUnit,
                numericSum,
                numericMinimum,
                numericMaximum,
                null);

        aggregate.requireCompatibleWith(requiredSensorType);
        return aggregate;
    }




    public static SensorSummaryAggregate emptyMotion() {
        return motion(0,0);
    }




    public static SensorSummaryAggregate motion(long sourceSampleCount, long trueSampleCount) {
        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                sourceSampleCount,
                null,
                null,
                null,
                null,
                trueSampleCount);

        aggregate.requireCompatibleWith(SensorType.MOTION);
        return aggregate;
    }



    static SensorSummaryAggregate restore(long sourceSampleCount, MeasurementUnit unit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum, Long trueSampleCount) {
        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                sourceSampleCount,
                unit,
                numericSum,
                numericMinimum,
                numericMaximum,
                trueSampleCount);

        aggregate.validateShape();
        return aggregate;
    }





    void requireCompatibleWith(SensorType sensorType) {
        SensorType requiredSensorType = Objects.requireNonNull(sensorType,"sensorType must not be null");
        validateShape();

        if (requiredSensorType == SensorType.MOTION) {
            if (!isMotion()) {
                throw new IllegalArgumentException("Motion sensors require motion summary values");
            }

            return;
        }

        MeasurementUnit expectedUnit = SensorMeasurementPolicy.requireCanonicalUnit(requiredSensorType);

        if (!isNumeric() || unit != expectedUnit) {
            throw new IllegalArgumentException(requiredSensorType + " summaries require canonical unit " + expectedUnit);
        }

        if (sourceSampleCount == 0) {
            return;
        }

        SensorMeasurementPolicy.requireValidNumericValue(requiredSensorType,numericMinimum,"numericMinimum");
        SensorMeasurementPolicy.requireValidNumericValue(requiredSensorType,numericMaximum,"numericMaximum");

        BigDecimal sampleCount = BigDecimal.valueOf(sourceSampleCount);
        BigDecimal minimumPossibleSum = BigDecimal.valueOf(numericMinimum).multiply(sampleCount);
        BigDecimal maximumPossibleSum = BigDecimal.valueOf(numericMaximum).multiply(sampleCount);

        if (numericSum.compareTo(minimumPossibleSum) < 0 || numericSum.compareTo(maximumPossibleSum) > 0) {
            throw new IllegalArgumentException("numericSum must be consistent with the sample count and numeric range");
        }
    }




    public boolean isNumeric() {
        return unit != null;
    }




    public boolean isMotion() {
        return unit == null;
    }






    private void validateShape() {
        if (sourceSampleCount < 0) {
            throw new IllegalArgumentException("sourceSampleCount must not be negative");
        }

        if (unit == null) {
            validateMotionShape();
            return;
        }

        validateNumericShape();
    }





    private void validateNumericShape() {
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





    private void validateMotionShape() {
        if (numericSum != null || numericMinimum != null || numericMaximum != null) {
            throw new IllegalArgumentException("Motion summaries must not contain numeric aggregate values");
        }

        if (trueSampleCount == null) {
            throw new IllegalArgumentException("Motion summaries must contain trueSampleCount");
        }

        if (trueSampleCount < 0 || trueSampleCount > sourceSampleCount) {
            throw new IllegalArgumentException("trueSampleCount must be between zero and sourceSampleCount");
        }
    }
}