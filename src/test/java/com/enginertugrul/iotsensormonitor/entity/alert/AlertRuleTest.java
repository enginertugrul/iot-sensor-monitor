package com.enginertugrul.iotsensormonitor.entity.alert;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertRuleTest {

    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(10);
    private static final Instant TRIGGERED_AT = CREATED_AT.plusSeconds(60).plusNanos(123);



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,-273.15,C",
            "TEMPERATURE,25.0,C",
            "HUMIDITY,0.0,PERCENT",
            "HUMIDITY,100.0,PERCENT"
    })
    void createsEnabledNumericRuleWithCanonicalThreshold(SensorType type,double threshold,MeasurementUnit expectedUnit) {
        Sensor sensor = sensor(type);

        AlertRule rule = AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,threshold,15);

        assertThat(rule.getSensor()).isSameAs(sensor);
        assertThat(rule.getOwner()).isSameAs(sensor.getOwner());
        assertThat(rule.getRuleType()).isEqualTo(AlertRuleType.NUMERIC_THRESHOLD);
        assertThat(rule.getComparisonOperator()).isEqualTo(ComparisonOperator.ABOVE);
        assertThat(rule.getThresholdValue()).isEqualTo(threshold);
        assertThat(rule.getThresholdUnit()).isEqualTo(expectedUnit);
        assertThat(rule.getEventType()).isNull();
        assertThat(rule.getCooldownMinutes()).isEqualTo(15);
        assertThat(rule.getLastTriggeredAt()).isNull();
        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.getCreatedAt()).isNotNull();
        assertThat(rule.getUpdatedAt()).isEqualTo(rule.getCreatedAt());
    }




    @Test
    void createsEnabledMotionRuleWithoutThresholdFields() {
        Sensor sensor = sensor(SensorType.MOTION);

        AlertRule rule = AlertRule.motionDetected(sensor,15);

        assertThat(rule.getSensor()).isSameAs(sensor);
        assertThat(rule.getOwner()).isSameAs(sensor.getOwner());
        assertThat(rule.getRuleType()).isEqualTo(AlertRuleType.EVENT_DETECTED);
        assertThat(rule.getEventType()).isEqualTo(AlertEventType.MOTION_DETECTED);
        assertThat(rule.getComparisonOperator()).isNull();
        assertThat(rule.getThresholdValue()).isNull();
        assertThat(rule.getThresholdUnit()).isNull();
        assertThat(rule.getCooldownMinutes()).isEqualTo(15);
        assertThat(rule.getLastTriggeredAt()).isNull();
        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.getCreatedAt()).isNotNull();
        assertThat(rule.getUpdatedAt()).isEqualTo(rule.getCreatedAt());
    }



    @Test
    void rejectsMissingSensorsInBothFactories() {
        assertThatNullPointerException()
                .isThrownBy(() -> AlertRule.numericThreshold(null,ComparisonOperator.ABOVE,25.0,60))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> AlertRule.motionDetected(null,60))
                .withMessage("sensor must not be null");
    }



    @Test
    void rejectsMissingComparisonOperator() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatNullPointerException()
                .isThrownBy(() -> AlertRule.numericThreshold(sensor,null,25.0,60))
                .withMessage("comparisonOperator must not be null");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(doubles = {Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY})
    void rejectsMissingOrNonFiniteThresholds(Double threshold) {
        Sensor temperatureSensor = sensor(SensorType.TEMPERATURE);
        Sensor humiditySensor = sensor(SensorType.HUMIDITY);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(temperatureSensor,ComparisonOperator.ABOVE,threshold,60))
                .withMessage("canonicalThresholdValue must be a finite number");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(humiditySensor,ComparisonOperator.ABOVE,threshold,60))
                .withMessage("canonicalThresholdValue must be a finite number");
    }



    @Test
    void rejectsThresholdsImmediatelyOutsideSensorBounds() {
        Sensor temperatureSensor = sensor(SensorType.TEMPERATURE);
        Sensor humiditySensor = sensor(SensorType.HUMIDITY);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(temperatureSensor,ComparisonOperator.BELOW,Math.nextDown(-273.15),60))
                .withMessage("canonicalThresholdValue is outside the valid range for TEMPERATURE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(humiditySensor,ComparisonOperator.BELOW,Math.nextDown(0.0),60))
                .withMessage("canonicalThresholdValue is outside the valid range for HUMIDITY");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(humiditySensor,ComparisonOperator.ABOVE,Math.nextUp(100.0),60))
                .withMessage("canonicalThresholdValue is outside the valid range for HUMIDITY");
    }



    @Test
    void rejectsNumericRulesForMotionSensors() {
        Sensor sensor = sensor(SensorType.MOTION);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,1.0,60))
                .withMessage("MOTION does not support numeric measurements");
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = {"TEMPERATURE","HUMIDITY"})
    void rejectsMotionRulesForNumericSensors(SensorType type) {
        Sensor sensor = sensor(type);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.motionDetected(sensor,60))
                .withMessage("Motion rules require a motion sensor type");
    }



    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0,10081})
    void validatesCooldownInBothFactories(Integer cooldownMinutes) {
        Sensor temperatureSensor = sensor(SensorType.TEMPERATURE);
        Sensor motionSensor = sensor(SensorType.MOTION);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.numericThreshold(temperatureSensor,ComparisonOperator.ABOVE,25.0,cooldownMinutes));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> AlertRule.motionDetected(motionSensor,cooldownMinutes));
    }



    @ParameterizedTest
    @EnumSource(value = SensorType.class,names = {"TEMPERATURE","HUMIDITY"})
    void matchesStrictNumericBoundariesForTheSameTransientSensor(SensorType type) {
        Sensor sensor = sensor(type);
        AlertRule aboveRule = AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,50.0,60);
        AlertRule belowRule = AlertRule.numericThreshold(sensor,ComparisonOperator.BELOW,50.0,60);
        SensorReading below = numericReading(sensor,Math.nextDown(50.0));
        SensorReading equal = numericReading(sensor,50.0);
        SensorReading above = numericReading(sensor,Math.nextUp(50.0));

        assertThat(sensor.getId()).isNull();
        assertThat(aboveRule.isTriggeredBy(below)).isFalse();
        assertThat(aboveRule.isTriggeredBy(equal)).isFalse();
        assertThat(aboveRule.isTriggeredBy(above)).isTrue();
        assertThat(belowRule.isTriggeredBy(below)).isTrue();
        assertThat(belowRule.isTriggeredBy(equal)).isFalse();
        assertThat(belowRule.isTriggeredBy(above)).isFalse();
        assertThat(aboveRule.getLastTriggeredAt()).isNull();
        assertThat(belowRule.getLastTriggeredAt()).isNull();
    }



    @ParameterizedTest
    @CsvSource(value = {
            "1000,1000,true",
            "1000,1001,false",
            "null,null,false",
            "1000,null,false",
            "null,1000,false"
    },nullValues = "null")
    void comparesDistinctSensorInstancesByNonNullIdentity(Long ruleSensorId,Long readingSensorId,boolean expected) {
        Sensor ruleSensor = sensor(SensorType.TEMPERATURE);
        Sensor readingSensor = sensor(SensorType.TEMPERATURE);
        ReflectionTestUtils.setField(ruleSensor,"id",ruleSensorId);
        ReflectionTestUtils.setField(readingSensor,"id",readingSensorId);
        AlertRule rule = AlertRule.numericThreshold(ruleSensor,ComparisonOperator.ABOVE,25.0,60);
        SensorReading reading = SensorReading.temperature(readingSensor,30.0,RECORDED_AT);

        assertThat(readingSensor).isNotSameAs(ruleSensor);
        assertThat(rule.isTriggeredBy(reading)).isEqualTo(expected);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "null,null,C",
            "30.0,true,C",
            "30.0,false,C",
            "30.0,null,null",
            "30.0,null,PERCENT"
    },nullValues = "null")
    void rejectsNumericReadingsWithMissingValuesMixedShapesOrWrongUnits(Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AlertRule rule = AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,25.0,60);
        SensorReading reading = readingWithShape(sensor,numericValue,booleanValue,unit);

        assertThat(rule.isTriggeredBy(reading)).isFalse();
    }



    @Test
    void matchesDetectedMotionOnlyForItsSensor() {
        Sensor sensor = sensor(SensorType.MOTION);
        Sensor otherSensor = sensor(SensorType.MOTION);
        AlertRule rule = AlertRule.motionDetected(sensor,60);

        assertThat(rule.isTriggeredBy(SensorReading.motion(sensor,true,RECORDED_AT))).isTrue();
        assertThat(rule.isTriggeredBy(SensorReading.motion(sensor,false,RECORDED_AT))).isFalse();
        assertThat(rule.isTriggeredBy(SensorReading.motion(otherSensor,true,RECORDED_AT))).isFalse();
        assertThat(rule.getLastTriggeredAt()).isNull();
    }



    @ParameterizedTest
    @CsvSource(value = {
            "null,null,null",
            "1.0,true,null",
            "null,true,C",
            "null,true,PERCENT"
    },nullValues = "null")
    void rejectsMotionReadingsWithMissingBooleanOrNumericFields(Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        Sensor sensor = sensor(SensorType.MOTION);
        AlertRule rule = AlertRule.motionDetected(sensor,60);
        SensorReading reading = readingWithShape(sensor,numericValue,booleanValue,unit);

        assertThat(rule.isTriggeredBy(reading)).isFalse();
    }



    @Test
    void rejectsMissingReadingOrReadingSensor() {
        AlertRule rule = AlertRule.motionDetected(sensor(SensorType.MOTION),60);
        SensorReading readingWithoutSensor = mock(SensorReading.class);

        assertThatNullPointerException()
                .isThrownBy(() -> rule.isTriggeredBy(null))
                .withMessage("sensorReading must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> rule.isTriggeredBy(readingWithoutSensor))
                .withMessage("reading sensor must not be null");
    }



    @Test
    void enabledStateControlsEligibilityWhileReadingMatchingRemainsIndependent() {
        Sensor sensor = sensor(SensorType.MOTION);
        AlertRule rule = AlertRule.motionDetected(sensor,60);
        SensorReading reading = SensorReading.motion(sensor,true,RECORDED_AT);

        assertThat(rule.isCooldownActiveAt(RECORDED_AT)).isFalse();
        assertThat(rule.canTriggerAt(RECORDED_AT)).isTrue();

        rule.disable();

        assertThat(rule.isEnabled()).isFalse();
        assertThat(rule.isTriggeredBy(reading)).isTrue();
        assertThat(rule.canTriggerAt(RECORDED_AT)).isFalse();

        rule.enable();

        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.canTriggerAt(RECORDED_AT)).isTrue();
        assertThat(rule.getLastTriggeredAt()).isNull();
    }



    @ParameterizedTest
    @ValueSource(ints = {1,60,10080})
    void cooldownExpiresExactlyAtItsBoundary(int cooldownMinutes) {
        AlertRule rule = AlertRule.motionDetected(sensor(SensorType.MOTION),cooldownMinutes);
        Instant cooldownEndsAt = TRIGGERED_AT.plus(Duration.ofMinutes(cooldownMinutes));

        rule.markTriggered(TRIGGERED_AT);

        assertThat(rule.getLastTriggeredAt()).isEqualTo(TRIGGERED_AT);
        assertThat(rule.isCooldownActiveAt(TRIGGERED_AT.minusNanos(1))).isTrue();
        assertThat(rule.canTriggerAt(TRIGGERED_AT.minusNanos(1))).isFalse();
        assertThat(rule.isCooldownActiveAt(TRIGGERED_AT)).isTrue();
        assertThat(rule.canTriggerAt(TRIGGERED_AT)).isFalse();
        assertThat(rule.isCooldownActiveAt(cooldownEndsAt.minusNanos(1))).isTrue();
        assertThat(rule.canTriggerAt(cooldownEndsAt.minusNanos(1))).isFalse();
        assertThat(rule.isCooldownActiveAt(cooldownEndsAt)).isFalse();
        assertThat(rule.canTriggerAt(cooldownEndsAt)).isTrue();
        assertThat(rule.isCooldownActiveAt(cooldownEndsAt.plusNanos(1))).isFalse();
        assertThat(rule.canTriggerAt(cooldownEndsAt.plusNanos(1))).isTrue();
    }



    @Test
    void disablingAndEnablingPreserveExistingCooldown() {
        Sensor sensor = sensor(SensorType.MOTION);
        AlertRule rule = AlertRule.motionDetected(sensor,60);
        Instant cooldownEndsAt = TRIGGERED_AT.plus(Duration.ofMinutes(60));
        SensorReading reading = SensorReading.motion(sensor,true,TRIGGERED_AT.plusSeconds(1));
        rule.markTriggered(TRIGGERED_AT);

        assertThat(rule.isTriggeredBy(reading)).isTrue();
        assertThat(rule.canTriggerAt(reading.getRecordedAt())).isFalse();

        rule.disable();

        assertThat(rule.canTriggerAt(cooldownEndsAt)).isFalse();
        assertThat(rule.getLastTriggeredAt()).isEqualTo(TRIGGERED_AT);

        rule.enable();

        assertThat(rule.getLastTriggeredAt()).isEqualTo(TRIGGERED_AT);
        assertThat(rule.isCooldownActiveAt(cooldownEndsAt.minusNanos(1))).isTrue();
        assertThat(rule.canTriggerAt(cooldownEndsAt.minusNanos(1))).isFalse();
        assertThat(rule.canTriggerAt(cooldownEndsAt)).isTrue();
    }



    @Test
    void markingAnotherTriggerStartsCooldownFromTheNewTimestamp() {
        AlertRule rule = AlertRule.motionDetected(sensor(SensorType.MOTION),60);
        Instant nextTriggeredAt = TRIGGERED_AT.plus(Duration.ofMinutes(60));
        rule.markTriggered(TRIGGERED_AT);

        assertThat(rule.canTriggerAt(nextTriggeredAt)).isTrue();

        rule.markTriggered(nextTriggeredAt);

        Instant nextCooldownEndsAt = nextTriggeredAt.plus(Duration.ofMinutes(60));

        assertThat(rule.getLastTriggeredAt()).isEqualTo(nextTriggeredAt);
        assertThat(rule.canTriggerAt(nextTriggeredAt)).isFalse();
        assertThat(rule.isCooldownActiveAt(nextCooldownEndsAt.minusNanos(1))).isTrue();
        assertThat(rule.canTriggerAt(nextCooldownEndsAt)).isTrue();
    }



    @Test
    void rejectsMissingCooldownCheckTimestamp() {
        AlertRule rule = AlertRule.motionDetected(sensor(SensorType.MOTION),60);

        assertThatNullPointerException()
                .isThrownBy(() -> rule.isCooldownActiveAt(null))
                .withMessage("checkedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> rule.canTriggerAt(null))
                .withMessage("checkedAt must not be null");
    }



    @Test
    void rejectingMissingTriggerTimestampPreservesPreviousTrigger() {
        AlertRule rule = AlertRule.motionDetected(sensor(SensorType.MOTION),60);
        rule.markTriggered(TRIGGERED_AT);

        assertThatNullPointerException()
                .isThrownBy(() -> rule.markTriggered(null))
                .withMessage("triggeredAt must not be null");

        assertThat(rule.getLastTriggeredAt()).isEqualTo(TRIGGERED_AT);
    }



    private static SensorReading numericReading(Sensor sensor,double value) {
        return switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,RECORDED_AT);
            case HUMIDITY -> SensorReading.humidity(sensor,value,RECORDED_AT);
            case MOTION -> throw new IllegalArgumentException("Numeric test readings require a numeric sensor");
        };
    }



    private static SensorReading readingWithShape(Sensor sensor,Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        SensorReading reading = mock(SensorReading.class);
        when(reading.getSensor()).thenReturn(sensor);
        when(reading.getNumericValue()).thenReturn(numericValue);
        when(reading.getBooleanValue()).thenReturn(booleanValue);
        when(reading.getUnit()).thenReturn(unit);
        return reading;
    }
}