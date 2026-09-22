package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensorSummaryAggregatorTest {

    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(10);



    @ParameterizedTest
    @CsvSource(value = {
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT",
            "MOTION,null"
    },nullValues = "null")
    void createsTheCorrectEmptyShapeForEachSensorType(SensorType type,MeasurementUnit expectedUnit) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregator.empty(type);

        assertThat(aggregate.getSourceSampleCount()).isZero();
        assertThat(aggregate.getUnit()).isEqualTo(expectedUnit);
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
        assertThat(aggregate.isNumeric()).isEqualTo(expectedUnit != null);
        assertThat(aggregate.isBoolean()).isEqualTo(expectedUnit == null);

        if (expectedUnit == null) {
            assertThat(aggregate.getTrueSampleCount()).isZero();
        } else {
            assertThat(aggregate.getTrueSampleCount()).isNull();
        }
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void convertsNumericReadingToOneSampleWithCanonicalUnit(SensorType type,MeasurementUnit expectedUnit) {
        SensorReading reading = numericReading(type,0.1);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromReading(type,reading);

        assertThat(aggregate.isNumeric()).isTrue();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(1);
        assertThat(aggregate.getUnit()).isEqualTo(expectedUnit);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("0.1");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(0.1);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(0.1);
        assertThat(aggregate.getTrueSampleCount()).isNull();
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void convertsMotionReadingToOneBooleanSample(boolean detected) {
        SensorReading reading = SensorReading.motion(sensor(SensorType.MOTION),detected,RECORDED_AT);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromReading(SensorType.MOTION,reading);

        assertThat(aggregate.isBoolean()).isTrue();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(1);
        assertThat(aggregate.getTrueSampleCount()).isEqualTo(detected ? 1L : 0L);
        assertThat(aggregate.getUnit()).isNull();
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void convertsEmptyRawProjectionToTheCorrectEmptyAggregate(SensorType type) {
        RawSensorReadingAggregateProjection raw = rawAggregate(0,null,null,null,0);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(type,raw);

        assertThat(aggregate).usingRecursiveComparison().isEqualTo(SensorSummaryAggregator.empty(type));
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void convertsRawNumericProjectionWithoutReconstructingItsSum(SensorType type,MeasurementUnit expectedUnit) {
        RawSensorReadingAggregateProjection raw = rawAggregate(3,new BigDecimal("60.600"),10.1,30.3,0);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(type,raw);

        assertThat(aggregate.getSourceSampleCount()).isEqualTo(3);
        assertThat(aggregate.getUnit()).isEqualTo(expectedUnit);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("60.600");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(10.1);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(30.3);
        assertThat(aggregate.getTrueSampleCount()).isNull();
    }



    @Test
    void preservesRawSumBeyondExactDoubleIntegerPrecision() {
        BigDecimal exactSum = new BigDecimal("9007199254740993");
        RawSensorReadingAggregateProjection raw = rawAggregate(2,exactSum,4503599627370496.0,4503599627370497.0,0);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(SensorType.TEMPERATURE,raw);

        assertThat(aggregate.getSourceSampleCount()).isEqualTo(2);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("9007199254740993");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(4503599627370496.0);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(4503599627370497.0);
    }



    @Test
    void convertsRawMotionProjectionToBooleanCounts() {
        RawSensorReadingAggregateProjection raw = rawAggregate(10,null,null,null,3);

        SensorSummaryAggregate aggregate = SensorSummaryAggregator.fromRawReadings(SensorType.MOTION,raw);

        assertThat(aggregate.isBoolean()).isTrue();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(10);
        assertThat(aggregate.getTrueSampleCount()).isEqualTo(3);
        assertThat(aggregate.getUnit()).isNull();
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsNegativeSourceCountsFromRawProjections(SensorType type) {
        RawSensorReadingAggregateProjection raw = rawAggregate(-1,null,null,null,0);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregator.fromRawReadings(type,raw))
                .withMessage("sourceSampleCount must not be negative");
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void combinesNoInputsOrOnlyEmptyInputsIntoAnEmptyAggregate(SensorType type) {
        SensorSummaryAggregate empty = SensorSummaryAggregator.empty(type);

        SensorSummaryAggregate noInputs = SensorSummaryAggregator.combine(type,List.of());
        SensorSummaryAggregate emptyInputs = SensorSummaryAggregator.combine(type,List.of(empty,empty));

        assertThat(noInputs).usingRecursiveComparison().isEqualTo(empty);
        assertThat(emptyInputs).usingRecursiveComparison().isEqualTo(empty);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void combinesUnequalNumericGroupsUsingTheirOriginalCountsAndSums(SensorType type,MeasurementUnit unit) {
        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyNumeric(unit);
        SensorSummaryAggregate first = SensorSummaryAggregate.numeric(2,unit,new BigDecimal("30"),10.0,20.0);
        SensorSummaryAggregate second = SensorSummaryAggregate.numeric(3,unit,new BigDecimal("120"),30.0,50.0);

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(type,List.of(empty,first,second,empty));
        SensorSummaryAggregate reversed = SensorSummaryAggregator.combine(type,List.of(second,empty,first));

        assertThat(combined.getSourceSampleCount()).isEqualTo(5);
        assertThat(combined.getUnit()).isEqualTo(unit);
        assertThat(combined.getNumericSum()).isEqualByComparingTo("150");
        assertThat(combined.getNumericMinimum()).isEqualTo(10.0);
        assertThat(combined.getNumericMaximum()).isEqualTo(50.0);
        assertThat(combined.getTrueSampleCount()).isNull();
        assertThat(reversed).usingRecursiveComparison().isEqualTo(combined);
    }



    @Test
    void preservesNegativeExtremaWhenEveryTemperatureIsBelowZero() {
        SensorSummaryAggregate first = SensorSummaryAggregator.fromReading(SensorType.TEMPERATURE,numericReading(SensorType.TEMPERATURE,-40.0));
        SensorSummaryAggregate second = SensorSummaryAggregator.fromReading(SensorType.TEMPERATURE,numericReading(SensorType.TEMPERATURE,-10.0));

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.TEMPERATURE,List.of(first,second));

        assertThat(combined.getSourceSampleCount()).isEqualTo(2);
        assertThat(combined.getNumericSum()).isEqualByComparingTo("-50");
        assertThat(combined.getNumericMinimum()).isEqualTo(-40.0);
        assertThat(combined.getNumericMaximum()).isEqualTo(-10.0);
    }



    @Test
    void addsDecimalReadingValuesExactly() {
        SensorSummaryAggregate first = SensorSummaryAggregator.fromReading(SensorType.TEMPERATURE,numericReading(SensorType.TEMPERATURE,0.1));
        SensorSummaryAggregate second = SensorSummaryAggregator.fromReading(SensorType.TEMPERATURE,numericReading(SensorType.TEMPERATURE,0.2));

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.TEMPERATURE,List.of(first,second));

        assertThat(combined.getSourceSampleCount()).isEqualTo(2);
        assertThat(combined.getNumericSum()).isEqualByComparingTo("0.3");
        assertThat(combined.getNumericMinimum()).isEqualTo(0.1);
        assertThat(combined.getNumericMaximum()).isEqualTo(0.2);
    }



    @Test
    void allowsExactNumericSumLargerThanDoubleMaximum() {
        SensorReading reading = numericReading(SensorType.TEMPERATURE,Double.MAX_VALUE);
        SensorSummaryAggregate single = SensorSummaryAggregator.fromReading(SensorType.TEMPERATURE,reading);

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.TEMPERATURE,List.of(single,single));

        assertThat(combined.getSourceSampleCount()).isEqualTo(2);
        assertThat(combined.getNumericSum()).isEqualByComparingTo("3.5953862697246314E+308");
        assertThat(combined.getNumericMinimum()).isEqualTo(Double.MAX_VALUE);
        assertThat(combined.getNumericMaximum()).isEqualTo(Double.MAX_VALUE);
    }



    @Test
    void combinesUnequalMotionGroupsUsingTheirOriginalSampleCounts() {
        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyBoolean();
        SensorSummaryAggregate first = SensorSummaryAggregate.booleanSamples(2,2);
        SensorSummaryAggregate second = SensorSummaryAggregate.booleanSamples(8,1);

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.MOTION,List.of(empty,first,second,empty));

        assertThat(combined.isBoolean()).isTrue();
        assertThat(combined.getSourceSampleCount()).isEqualTo(10);
        assertThat(combined.getTrueSampleCount()).isEqualTo(3);
        assertThat(combined.getUnit()).isNull();
        assertThat(combined.getNumericSum()).isNull();
        assertThat(combined.getNumericMinimum()).isNull();
        assertThat(combined.getNumericMaximum()).isNull();
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,PERCENT",
            "HUMIDITY,C"
    })
    void rejectsIncompatibleUnitsForEmptyAndNonEmptyInputs(SensorType type,MeasurementUnit wrongUnit) {
        SensorSummaryAggregate wrongEmpty = SensorSummaryAggregate.emptyNumeric(wrongUnit);
        SensorSummaryAggregate wrongPopulated = SensorSummaryAggregate.numeric(1,wrongUnit,BigDecimal.ONE,1.0,1.0);
        SensorSummaryAggregate compatible = SensorSummaryAggregator.empty(type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregator.combine(type,List.of(wrongEmpty)))
                .withMessage("Summary aggregate unit does not match sensor type " + type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregator.combine(type,List.of(compatible,wrongPopulated)))
                .withMessage("Summary aggregate unit does not match sensor type " + type);
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = {"TEMPERATURE","HUMIDITY"})
    void rejectsMixedValueKindsEvenWhenInputsAreEmpty(SensorType type) {
        SensorSummaryAggregate numeric = SensorSummaryAggregator.empty(type);
        SensorSummaryAggregate bool = SensorSummaryAggregate.emptyBoolean();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregator.combine(type,List.of(numeric,bool)))
                .withMessage("Summary aggregate value kind does not match sensor type " + type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorSummaryAggregator.combine(SensorType.MOTION,List.of(bool,numeric)))
                .withMessage("Summary aggregate value kind does not match sensor type MOTION");
    }



    @Test
    void allowsNumericSourceCountToReachLongMaximumExactly() {
        SensorSummaryAggregate first = SensorSummaryAggregate.numeric(Long.MAX_VALUE - 1,MeasurementUnit.C,BigDecimal.ZERO,0.0,0.0);
        SensorSummaryAggregate second = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,BigDecimal.ZERO,0.0,0.0);

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.TEMPERATURE,List.of(first,second));

        assertThat(combined.getSourceSampleCount()).isEqualTo(Long.MAX_VALUE);
        assertThat(combined.getNumericSum()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(combined.getNumericMinimum()).isEqualTo(0.0);
        assertThat(combined.getNumericMaximum()).isEqualTo(0.0);
    }



    @Test
    void rejectsNumericSourceCountOverflow() {
        SensorSummaryAggregate first = SensorSummaryAggregate.numeric(Long.MAX_VALUE,MeasurementUnit.C,BigDecimal.ZERO,0.0,0.0);
        SensorSummaryAggregate second = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,BigDecimal.ZERO,0.0,0.0);

        assertThatThrownBy(() -> SensorSummaryAggregator.combine(SensorType.TEMPERATURE,List.of(first,second)))
                .isInstanceOf(ArithmeticException.class);
    }



    @Test
    void allowsBooleanSourceAndTrueCountsToReachLongMaximumExactly() {
        SensorSummaryAggregate first = SensorSummaryAggregate.booleanSamples(Long.MAX_VALUE - 1,Long.MAX_VALUE - 1);
        SensorSummaryAggregate second = SensorSummaryAggregate.booleanSamples(1,1);

        SensorSummaryAggregate combined = SensorSummaryAggregator.combine(SensorType.MOTION,List.of(first,second));

        assertThat(combined.getSourceSampleCount()).isEqualTo(Long.MAX_VALUE);
        assertThat(combined.getTrueSampleCount()).isEqualTo(Long.MAX_VALUE);
    }



    @Test
    void rejectsBooleanSourceCountOverflowEvenWhenTrueCountIsZero() {
        SensorSummaryAggregate first = SensorSummaryAggregate.booleanSamples(Long.MAX_VALUE,0);
        SensorSummaryAggregate second = SensorSummaryAggregate.booleanSamples(1,0);

        assertThatThrownBy(() -> SensorSummaryAggregator.combine(SensorType.MOTION,List.of(first,second)))
                .isInstanceOf(ArithmeticException.class);
    }



    private static SensorReading numericReading(SensorType type,double value) {
        Sensor sensor = sensor(type);

        return switch (type) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,RECORDED_AT);
            case HUMIDITY -> SensorReading.humidity(sensor,value,RECORDED_AT);
            case MOTION -> throw new IllegalArgumentException("Numeric test readings require a numeric sensor");
        };
    }



    private static RawSensorReadingAggregateProjection rawAggregate(long sourceCount,BigDecimal sum,Double minimum,Double maximum,long trueCount) {
        RawSensorReadingAggregateProjection raw = mock(RawSensorReadingAggregateProjection.class);
        when(raw.getSourceSampleCount()).thenReturn(sourceCount);
        when(raw.getNumericSum()).thenReturn(sum);
        when(raw.getNumericMinimum()).thenReturn(minimum);
        when(raw.getNumericMaximum()).thenReturn(maximum);
        when(raw.getTrueSampleCount()).thenReturn(trueCount);
        return raw;
    }
}