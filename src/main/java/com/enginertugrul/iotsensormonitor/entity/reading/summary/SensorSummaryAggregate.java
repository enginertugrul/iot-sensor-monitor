package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.DomainChecks;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
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




    public static SensorSummaryAggregate emptyNumeric(MeasurementUnit canonicalUnit) {
        return numeric(0,canonicalUnit,null,null,null);
    }




    public static SensorSummaryAggregate numeric(long sourceSampleCount, MeasurementUnit canonicalUnit ,BigDecimal numericSum, Double numericMinimum, Double numericMaximum) {


        SensorSummaryAggregate aggregate = new SensorSummaryAggregate(
                sourceSampleCount,
                canonicalUnit,
                numericSum,
                numericMinimum,
                numericMaximum,
                null);

        aggregate.validateShape();

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

        aggregate.validateShape();

        return aggregate;
    }



    static SensorSummaryAggregate restore(long sourceSampleCount, MeasurementUnit unit, BigDecimal numericSum, Double numericMinimum, Double numericMaximum, Long trueSampleCount) {

        return new SensorSummaryAggregate(
                sourceSampleCount,
                unit,
                numericSum,
                numericMinimum,
                numericMaximum,
                trueSampleCount);
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