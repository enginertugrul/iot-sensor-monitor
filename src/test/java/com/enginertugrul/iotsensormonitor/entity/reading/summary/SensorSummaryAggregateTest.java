package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.ReadingValueKind;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensorSummaryAggregateTest {



    @ParameterizedTest
    @EnumSource(MeasurementUnit.class)
    void emptyNumericAggregateRetainsItsUnitWithoutNumericValues(MeasurementUnit unit) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.emptyNumeric(unit);

        assertThat(aggregate.getReadingValueKind()).isEqualTo(ReadingValueKind.NUMERIC);
        assertThat(aggregate.isNumeric()).isTrue();
        assertThat(aggregate.isBoolean()).isFalse();
        assertThat(aggregate.getSourceSampleCount()).isZero();
        assertThat(aggregate.getUnit()).isEqualTo(unit);
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
        assertThat(aggregate.getTrueSampleCount()).isNull();
    }



    @Test
    void emptyBooleanAggregateHasZeroTrueSamplesAndNoNumericFields() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.emptyBoolean();

        assertThat(aggregate.getReadingValueKind()).isEqualTo(ReadingValueKind.BOOLEAN);
        assertThat(aggregate.isBoolean()).isTrue();
        assertThat(aggregate.isNumeric()).isFalse();
        assertThat(aggregate.getSourceSampleCount()).isZero();
        assertThat(aggregate.getTrueSampleCount()).isZero();
        assertThat(aggregate.getUnit()).isNull();
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }



    @ParameterizedTest
    @EnumSource(MeasurementUnit.class)
    void createsNumericAggregateWithCountSumAndExtrema(MeasurementUnit unit) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(3,unit,new BigDecimal("60.600"),10.1,30.3);

        assertThat(aggregate.isNumeric()).isTrue();
        assertThat(aggregate.isBoolean()).isFalse();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(3);
        assertThat(aggregate.getUnit()).isEqualTo(unit);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("60.600");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(10.1);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(30.3);
        assertThat(aggregate.getTrueSampleCount()).isNull();
    }



    @Test
    void acceptsEqualNumericMinimumAndMaximum() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("40"),20.0,20.0);

        assertThat(aggregate.getSourceSampleCount()).isEqualTo(2);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("40");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(20.0);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(20.0);
    }



    @Test
    void zeroNumericSumDoesNotMakeAnAggregateEmpty() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(3,MeasurementUnit.C,BigDecimal.ZERO,-10.0,10.0);

        assertThat(aggregate.getSourceSampleCount()).isEqualTo(3);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(aggregate.getNumericMinimum()).isEqualTo(-10.0);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(10.0);
    }



    @ParameterizedTest
    @CsvSource({
            "2,0",
            "2,1",
            "2,2"
    })
    void createsBooleanAggregateWithTrueCountWithinInclusiveBounds(long sourceCount,long trueCount) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.booleanSamples(sourceCount,trueCount);

        assertThat(aggregate.isBoolean()).isTrue();
        assertThat(aggregate.isNumeric()).isFalse();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(sourceCount);
        assertThat(aggregate.getTrueSampleCount()).isEqualTo(trueCount);
        assertThat(aggregate.getUnit()).isNull();
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,Long.MIN_VALUE})
    void rejectsNegativeSourceCountsForBothValueKinds(long sourceCount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(sourceCount,MeasurementUnit.C,BigDecimal.ZERO,0.0,0.0))
                .withMessage("sourceSampleCount must not be negative");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.booleanSamples(sourceCount,0))
                .withMessage("sourceSampleCount must not be negative");
    }



    @Test
    void rejectsMissingNumericUnitForEmptyAndNonEmptyAggregates() {
        assertThatNullPointerException()
                .isThrownBy(() -> SensorSummaryAggregate.emptyNumeric(null))
                .withMessage("unit must not be null for numeric summaries");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(1,null,BigDecimal.ONE,1.0,1.0))
                .withMessage("unit must not be null for numeric summaries");
    }



    @Test
    void rejectsNumericValuesWhenSourceCountIsZero() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(0,MeasurementUnit.C,BigDecimal.ZERO,null,null))
                .withMessage("Empty numeric summaries must not contain numeric aggregate values");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(0,MeasurementUnit.C,null,0.0,null))
                .withMessage("Empty numeric summaries must not contain numeric aggregate values");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(0,MeasurementUnit.C,null,null,0.0))
                .withMessage("Empty numeric summaries must not contain numeric aggregate values");
    }



    @Test
    void rejectsMissingSumWhenSourceCountIsPositive() {
        assertThatNullPointerException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(1,MeasurementUnit.C,null,20.0,20.0))
                .withMessage("numericSum must not be null when sourceSampleCount is positive");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteNumericExtrema(Double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),value,20.0))
                .withMessage("numericMinimum must be a finite number");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,value))
                .withMessage("numericMaximum must be a finite number");
    }



    @Test
    void rejectsMinimumGreaterThanMaximum() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("30"),20.0,10.0))
                .withMessage("numericMinimum must not exceed numericMaximum");
    }



    @ParameterizedTest
    @CsvSource({
            "0,1",
            "3,-1",
            "3,4"
    })
    void rejectsTrueCountOutsideSourceCountBounds(long sourceCount,long trueCount) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregate.booleanSamples(sourceCount,trueCount))
                .withMessage("trueSampleCount must be between zero and sourceSampleCount");
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void acceptsCompatibleNumericSensorTypes(SensorType type,MeasurementUnit unit) {
        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyNumeric(unit);
        SensorSummaryAggregate populated = SensorSummaryAggregate.numeric(1,unit,new BigDecimal("20"),20.0,20.0);

        assertThatNoException().isThrownBy(() -> empty.requireCompatibleWith(type));
        assertThatNoException().isThrownBy(() -> populated.requireCompatibleWith(type));
    }



    @Test
    void acceptsBooleanAggregatesForMotionSensors() {
        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyBoolean();
        SensorSummaryAggregate populated = SensorSummaryAggregate.booleanSamples(3,2);

        assertThatNoException().isThrownBy(() -> empty.requireCompatibleWith(SensorType.MOTION));
        assertThatNoException().isThrownBy(() -> populated.requireCompatibleWith(SensorType.MOTION));
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,PERCENT",
            "HUMIDITY,C"
    })
    void rejectsIncompatibleNumericUnitsEvenForEmptyAggregates(SensorType type,MeasurementUnit unit) {
        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyNumeric(unit);
        SensorSummaryAggregate populated = SensorSummaryAggregate.numeric(1,unit,new BigDecimal("20"),20.0,20.0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> empty.requireCompatibleWith(type))
                .withMessage("Summary aggregate unit does not match sensor type " + type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> populated.requireCompatibleWith(type))
                .withMessage("Summary aggregate unit does not match sensor type " + type);
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = {"TEMPERATURE","HUMIDITY"})
    void rejectsBooleanAggregatesForNumericSensors(SensorType type) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.booleanSamples(3,2);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> aggregate.requireCompatibleWith(type))
                .withMessage("Summary aggregate value kind does not match sensor type " + type);
    }



    @Test
    void rejectsNumericAggregatesForMotionSensors() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,20.0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> aggregate.requireCompatibleWith(SensorType.MOTION))
                .withMessage("Summary aggregate value kind does not match sensor type MOTION");
    }



    @Test
    void rejectsMissingSensorTypeForCompatibilityChecks() {
        SensorSummaryAggregate numeric = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        SensorSummaryAggregate bool = SensorSummaryAggregate.emptyBoolean();

        assertThatNullPointerException()
                .isThrownBy(() -> numeric.requireCompatibleWith(null))
                .withMessage("sensorType must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> bool.requireCompatibleWith(null))
                .withMessage("sensorType must not be null");
    }
}