package com.enginertugrul.iotsensormonitor.entity.measurement;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensorMeasurementPolicyTest {

    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,true",
            "HUMIDITY,true",
            "MOTION,false"
    })
    void identifiesNumericSensorTypes(SensorType sensorType,boolean expected) {
        assertThat(SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void returnsCanonicalUnitForNumericSensors(SensorType sensorType,MeasurementUnit expected) {
        assertThat(SensorMeasurementPolicy.requireCanonicalUnit(sensorType)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(doubles = {-273.15,-40.0,0.0,21.5,Double.MAX_VALUE})
    void acceptsTemperaturesFromAbsoluteZeroThroughLargestFiniteValue(double value) {
        assertThat(SensorMeasurementPolicy.requireValidNumericValue(SensorType.TEMPERATURE,value,"celsiusValue"))
                .isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("temperaturesBelowAbsoluteZero")
    void rejectsTemperaturesBelowAbsoluteZero(double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(SensorType.TEMPERATURE,value,"celsiusValue"))
                .withMessage("celsiusValue is outside the valid range for TEMPERATURE");
    }

    @ParameterizedTest
    @ValueSource(doubles = {-0.0,0.0,Double.MIN_VALUE,50.0,100.0})
    void acceptsHumidityWithinInclusivePercentageBounds(double value) {
        assertThat(SensorMeasurementPolicy.requireValidNumericValue(SensorType.HUMIDITY,value,"humidityPercentage"))
                .isEqualTo(value);
    }

    @ParameterizedTest
    @MethodSource("humidityOutsidePercentageBounds")
    void rejectsHumidityOutsidePercentageBounds(double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(SensorType.HUMIDITY,value,"humidityPercentage"))
                .withMessage("humidityPercentage is outside the valid range for HUMIDITY");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteValuesForBothNumericSensorTypes(Double value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(SensorType.TEMPERATURE,value,"celsiusValue"))
                .withMessage("celsiusValue must be a finite number");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(SensorType.HUMIDITY,value,"humidityPercentage"))
                .withMessage("humidityPercentage must be a finite number");
    }

    @Test
    void rejectsCanonicalUnitLookupForMotionSensors() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireCanonicalUnit(SensorType.MOTION))
                .withMessage("MOTION does not support numeric measurements");
    }

    @Test
    void rejectsNumericValuesForMotionSensors() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(SensorType.MOTION,1.0,"numericValue"))
                .withMessage("MOTION does not support numeric measurements");
    }

    @Test
    void rejectsNullSensorTypes() {
        assertThatNullPointerException()
                .isThrownBy(() -> SensorMeasurementPolicy.supportsNumericMeasurements(null))
                .withMessage("sensorType must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireCanonicalUnit(null))
                .withMessage("sensorType must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorMeasurementPolicy.requireValidNumericValue(null,20.0,"numericValue"))
                .withMessage("sensorType must not be null");
    }

    private static Stream<Double> temperaturesBelowAbsoluteZero() {
        return Stream.of(Math.nextDown(-273.15),-300.0,-Double.MAX_VALUE);
    }

    private static Stream<Double> humidityOutsidePercentageBounds() {
        return Stream.of(-Double.MAX_VALUE,-1.0,Math.nextDown(0.0),Math.nextUp(100.0),101.0,Double.MAX_VALUE);
    }
}