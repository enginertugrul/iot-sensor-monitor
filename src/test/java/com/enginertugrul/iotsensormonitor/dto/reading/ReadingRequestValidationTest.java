package com.enginertugrul.iotsensormonitor.dto.reading;

import com.enginertugrul.iotsensormonitor.validation.IsFiniteDouble;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.Set;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;

class ReadingRequestValidationTest {

    private static final String SENSOR_TOKEN = "test-sensor-token";
    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(10);

    private static ValidatorFactory validatorFactory;
    private static Validator validator;



    @BeforeAll
    static void createValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }



    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }



    @ParameterizedTest
    @ValueSource(doubles = {-273.15,-20.0,0.0,21.5,Double.MAX_VALUE})
    void acceptsFiniteTemperaturesAtOrAboveAbsoluteZero(double value) {
        TemperatureReadingRequest request = new TemperatureReadingRequest(SENSOR_TOKEN,value,RECORDED_AT);

        assertThat(validator.validate(request)).isEmpty();
    }



    @Test
    void rejectsTemperatureImmediatelyBelowAbsoluteZero() {
        TemperatureReadingRequest request = new TemperatureReadingRequest(SENSOR_TOKEN,Math.nextDown(-273.15),RECORDED_AT);

        assertOnlyViolation(request,"celsiusValue",DecimalMin.class);
    }



    @ParameterizedTest
    @ValueSource(doubles = {0.0,0.1,50.0,100.0})
    void acceptsHumidityWithinInclusivePercentageBounds(double value) {
        HumidityReadingRequest request = new HumidityReadingRequest(SENSOR_TOKEN,value,RECORDED_AT);

        assertThat(validator.validate(request)).isEmpty();
    }



    @Test
    void rejectsHumidityImmediatelyOutsidePercentageBounds() {
        HumidityReadingRequest belowMinimum = new HumidityReadingRequest(SENSOR_TOKEN,Math.nextDown(0.0),RECORDED_AT);
        HumidityReadingRequest aboveMaximum = new HumidityReadingRequest(SENSOR_TOKEN,Math.nextUp(100.0),RECORDED_AT);

        assertOnlyViolation(belowMinimum,"humidityPercentage",DecimalMin.class);
        assertOnlyViolation(aboveMaximum,"humidityPercentage",DecimalMax.class);
    }



    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsNonFiniteTemperatureAndHumidityValues(double value) {
        TemperatureReadingRequest temperature = new TemperatureReadingRequest(SENSOR_TOKEN,value,RECORDED_AT);
        HumidityReadingRequest humidity = new HumidityReadingRequest(SENSOR_TOKEN,value,RECORDED_AT);

        assertViolation(validator.validate(temperature),"celsiusValue",IsFiniteDouble.class);
        assertViolation(validator.validate(humidity),"humidityPercentage",IsFiniteDouble.class);
    }



    @Test
    void requiresNumericValuesWithoutAddingFiniteNumberErrorsForNull() {
        TemperatureReadingRequest temperature = new TemperatureReadingRequest(SENSOR_TOKEN,null,RECORDED_AT);
        HumidityReadingRequest humidity = new HumidityReadingRequest(SENSOR_TOKEN,null,RECORDED_AT);

        assertOnlyViolation(temperature,"celsiusValue",NotNull.class);
        assertOnlyViolation(humidity,"humidityPercentage",NotNull.class);
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void acceptsBothMotionStates(boolean motionDetected) {
        MotionReadingRequest request = new MotionReadingRequest(SENSOR_TOKEN,motionDetected,RECORDED_AT);

        assertThat(validator.validate(request)).isEmpty();
    }



    @Test
    void requiresMotionState() {
        MotionReadingRequest request = new MotionReadingRequest(SENSOR_TOKEN,null,RECORDED_AT);

        assertOnlyViolation(request,"motionDetected",NotNull.class);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n"})
    void requiresNonblankTokensForEveryReadingType(String token) {
        assertOnlyViolation(new TemperatureReadingRequest(token,20.0,RECORDED_AT),"sensorToken",NotBlank.class);
        assertOnlyViolation(new HumidityReadingRequest(token,50.0,RECORDED_AT),"sensorToken",NotBlank.class);
        assertOnlyViolation(new MotionReadingRequest(token,false,RECORDED_AT),"sensorToken",NotBlank.class);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,256})
    void acceptsNonblankTokensThroughMaximumLength(int length) {
        String token = "t".repeat(length);

        assertThat(validator.validate(new TemperatureReadingRequest(token,20.0,RECORDED_AT))).isEmpty();
        assertThat(validator.validate(new HumidityReadingRequest(token,50.0,RECORDED_AT))).isEmpty();
        assertThat(validator.validate(new MotionReadingRequest(token,false,RECORDED_AT))).isEmpty();
    }



    @Test
    void rejectsTokensImmediatelyAboveMaximumLength() {
        String token = "t".repeat(257);

        assertOnlyViolation(new TemperatureReadingRequest(token,20.0,RECORDED_AT),"sensorToken",Size.class);
        assertOnlyViolation(new HumidityReadingRequest(token,50.0,RECORDED_AT),"sensorToken",Size.class);
        assertOnlyViolation(new MotionReadingRequest(token,false,RECORDED_AT),"sensorToken",Size.class);
    }



    @Test
    void requiresRecordedTimestampForEveryReadingType() {
        assertOnlyViolation(new TemperatureReadingRequest(SENSOR_TOKEN,20.0,null),"recordedAt",NotNull.class);
        assertOnlyViolation(new HumidityReadingRequest(SENSOR_TOKEN,50.0,null),"recordedAt",NotNull.class);
        assertOnlyViolation(new MotionReadingRequest(SENSOR_TOKEN,false,null),"recordedAt",NotNull.class);
    }



    private static void assertOnlyViolation(Object request,String property,Class<? extends Annotation> constraint) {
        var violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertViolation(violations,property,constraint);
    }



    private static void assertViolation(Set<? extends ConstraintViolation<?>> violations,String property,Class<? extends Annotation> constraint) {
        assertThat(violations).anySatisfy(violation -> {
            assertThat(violation.getPropertyPath().toString()).isEqualTo(property);
            assertThat(violation.getConstraintDescriptor().getAnnotation().annotationType()).isEqualTo(constraint);
        });
    }
}