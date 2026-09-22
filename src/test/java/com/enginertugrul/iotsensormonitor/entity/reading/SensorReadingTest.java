package com.enginertugrul.iotsensormonitor.entity.reading;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensorReadingTest {

    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(10).plusNanos(123);



    @ParameterizedTest
    @ValueSource(doubles = {-273.15,0.0,21.5,Double.MAX_VALUE})
    void createsTemperatureReadingWithCanonicalUnitAndNumericShape(double value) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        SensorReading reading = SensorReading.temperature(sensor,value,RECORDED_AT);

        assertThat(reading.getId()).isNull();
        assertThat(reading.getSensor()).isSameAs(sensor);
        assertThat(reading.getNumericValue()).isEqualTo(value);
        assertThat(reading.getBooleanValue()).isNull();
        assertThat(reading.getUnit()).isEqualTo(MeasurementUnit.C);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @ValueSource(doubles = {0.0,47.25,100.0})
    void createsHumidityReadingWithCanonicalUnitAndNumericShape(double value) {
        Sensor sensor = sensor(SensorType.HUMIDITY);

        SensorReading reading = SensorReading.humidity(sensor,value,RECORDED_AT);

        assertThat(reading.getId()).isNull();
        assertThat(reading.getSensor()).isSameAs(sensor);
        assertThat(reading.getNumericValue()).isEqualTo(value);
        assertThat(reading.getBooleanValue()).isNull();
        assertThat(reading.getUnit()).isEqualTo(MeasurementUnit.PERCENT);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void createsMotionReadingWithBooleanShapeAndNoUnit(boolean motionDetected) {
        Sensor sensor = sensor(SensorType.MOTION);

        SensorReading reading = SensorReading.motion(sensor,motionDetected,RECORDED_AT);

        assertThat(reading.getId()).isNull();
        assertThat(reading.getSensor()).isSameAs(sensor);
        assertThat(reading.getBooleanValue()).isEqualTo(motionDetected);
        assertThat(reading.getNumericValue()).isNull();
        assertThat(reading.getUnit()).isNull();
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);
        assertThat(sensor.hasRecordedReadings()).isFalse();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
    }



    @Test
    void rejectsMissingSensorForEveryReadingType() {
        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.temperature(null,20.0,RECORDED_AT))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.humidity(null,50.0,RECORDED_AT))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.motion(null,true,RECORDED_AT))
                .withMessage("sensor must not be null");
    }



    @Test
    void rejectsMissingRecordedTimestampForEveryReadingType() {
        Sensor temperatureSensor = sensor(SensorType.TEMPERATURE);
        Sensor humiditySensor = sensor(SensorType.HUMIDITY);
        Sensor motionSensor = sensor(SensorType.MOTION);

        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.temperature(temperatureSensor,20.0,null))
                .withMessage("recordedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.humidity(humiditySensor,50.0,null))
                .withMessage("recordedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorReading.motion(motionSensor,true,null))
                .withMessage("recordedAt must not be null");
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = "TEMPERATURE",mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonTemperatureSensorsInTemperatureFactory(SensorType type) {
        Sensor sensor = sensor(type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.temperature(sensor,20.0,RECORDED_AT))
                .withMessage("sensor type must be TEMPERATURE");
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = "HUMIDITY",mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonHumiditySensorsInHumidityFactory(SensorType type) {
        Sensor sensor = sensor(type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.humidity(sensor,50.0,RECORDED_AT))
                .withMessage("sensor type must be HUMIDITY");
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = "MOTION",mode = EnumSource.Mode.EXCLUDE)
    void rejectsNonMotionSensorsInMotionFactory(SensorType type) {
        Sensor sensor = sensor(type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.motion(sensor,true,RECORDED_AT))
                .withMessage("sensor type must be MOTION");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteTemperature(Double value) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.temperature(sensor,value,RECORDED_AT))
                .withMessage("celsiusValue must be a finite number");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteHumidity(Double value) {
        Sensor sensor = sensor(SensorType.HUMIDITY);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.humidity(sensor,value,RECORDED_AT))
                .withMessage("humidityPercentage must be a finite number");
    }



    @Test
    void rejectsTemperatureImmediatelyBelowAbsoluteZero() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.temperature(sensor,Math.nextDown(-273.15),RECORDED_AT))
                .withMessage("celsiusValue is outside the valid range for TEMPERATURE");
    }



    @Test
    void rejectsHumidityImmediatelyOutsidePercentageBounds() {
        Sensor sensor = sensor(SensorType.HUMIDITY);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.humidity(sensor,Math.nextDown(0.0),RECORDED_AT))
                .withMessage("humidityPercentage is outside the valid range for HUMIDITY");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorReading.humidity(sensor,Math.nextUp(100.0),RECORDED_AT))
                .withMessage("humidityPercentage is outside the valid range for HUMIDITY");
    }
}