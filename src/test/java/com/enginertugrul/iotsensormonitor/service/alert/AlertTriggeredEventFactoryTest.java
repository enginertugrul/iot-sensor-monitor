package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneId;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class AlertTriggeredEventFactoryTest {

    private static final Long SENSOR_ID = 100L;
    private static final Long RULE_ID = 200L;
    private static final int COOLDOWN_MINUTES = 10;
    private static final Instant RECORDED_AT = CREATED_AT.plusSeconds(3600).plusNanos(123456000);

    private final AlertTriggeredEventFactory factory = new AlertTriggeredEventFactory();



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,ABOVE,25.0,20.0,C",
            "TEMPERATURE,BELOW,15.0,20.0,C",
            "HUMIDITY,ABOVE,70.0,60.0,PERCENT",
            "HUMIDITY,BELOW,40.0,60.0,PERCENT"
    })
    void copiesNumericTriggerAndCompleteContextWithoutConvertingCanonicalValues(SensorType type,ComparisonOperator operator,double readingValue,double threshold,MeasurementUnit unit) {
        Sensor sensor = sensor(type);
        AlertRule rule = identifiedRule(AlertRule.numericThreshold(sensor,operator,threshold,COOLDOWN_MINUTES));
        SensorReading reading = type == SensorType.TEMPERATURE
                ? SensorReading.temperature(sensor,readingValue,RECORDED_AT)
                : SensorReading.humidity(sensor,readingValue,RECORDED_AT);

        AlertTriggeredEvent event = factory.from(rule,reading);

        AlertTriggeredEvent expected = new AlertTriggeredEvent(expectedContext(type),
                new AlertTriggeredEvent.NumericThresholdTrigger(operator,readingValue,threshold,unit));
        assertThat(event).isEqualTo(expected);
    }



    @Test
    void copiesMotionTriggerAndCompleteContext() {
        Sensor sensor = sensor(SensorType.MOTION);
        AlertRule rule = identifiedRule(AlertRule.motionDetected(sensor,COOLDOWN_MINUTES));
        SensorReading reading = SensorReading.motion(sensor,true,RECORDED_AT);

        AlertTriggeredEvent event = factory.from(rule,reading);

        assertThat(event).isEqualTo(new AlertTriggeredEvent(expectedContext(SensorType.MOTION),new AlertTriggeredEvent.MotionDetectedTrigger()));
    }



    @Test
    void keepsExistingSnapshotIndependentOfLaterEntityChanges() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        AlertRule rule = identifiedRule(AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,20.0,COOLDOWN_MINUTES));
        SensorReading reading = SensorReading.temperature(sensor,25.0,RECORDED_AT);
        AlertTriggeredEvent event = factory.from(rule,reading);
        Instant changedAt = RECORDED_AT.plusSeconds(60);

        sensor.getOwner().updatePreferences(PreferredLanguage.ENGLISH,TemperatureUnit.KELVIN,"Asia/Tokyo",changedAt);
        sensor.updateDetails("Renamed sensor","Ankara","Cankaya","Door","UTC",changedAt);
        rule.disable();
        rule.markTriggered(changedAt);

        AlertTriggeredEvent expected = new AlertTriggeredEvent(expectedContext(SensorType.TEMPERATURE),
                new AlertTriggeredEvent.NumericThresholdTrigger(ComparisonOperator.ABOVE,25.0,20.0,MeasurementUnit.C));
        assertThat(event).isEqualTo(expected);

        AlertTriggeredEvent refreshed = factory.from(rule,reading);
        assertThat(refreshed.context().sensor().name()).isEqualTo("Renamed sensor");
        assertThat(refreshed.context().sensor().timezone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(refreshed.context().recipient().preferredTemperatureUnit()).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(refreshed.context().recipient().timezone()).isEqualTo(ZoneId.of("Asia/Tokyo"));
        assertThat(refreshed.context().recordedAt()).isEqualTo(RECORDED_AT);
        assertThat(refreshed).isNotEqualTo(event);
    }



    @Test
    void rejectsMissingRuleAndReading() {
        Sensor sensor = sensor(SensorType.MOTION);
        AlertRule rule = identifiedRule(AlertRule.motionDetected(sensor,COOLDOWN_MINUTES));
        SensorReading reading = SensorReading.motion(sensor,true,RECORDED_AT);

        assertThatThrownBy(() -> factory.from(null,reading))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("rule must not be null");

        assertThatThrownBy(() -> factory.from(rule,null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("reading must not be null");
    }



    private static Sensor sensor(SensorType type) {
        AppUser owner = new AppUser(" OWNER@EXAMPLE.COM ","test-password-hash",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"America/New_York",CREATED_AT);
        Sensor sensor = new Sensor(owner,type,"Living room","Istanbul","Kadikoy","Window","Europe/Istanbul",CREATED_AT);
        ReflectionTestUtils.setField(owner,"id",42L);
        ReflectionTestUtils.setField(sensor,"id",SENSOR_ID);
        return sensor;
    }



    private static AlertRule identifiedRule(AlertRule rule) {
        ReflectionTestUtils.setField(rule,"id",RULE_ID);
        return rule;
    }



    private static AlertTriggeredEvent.Context expectedContext(SensorType type) {
        AlertTriggeredEvent.RecipientSnapshot recipient = new AlertTriggeredEvent.RecipientSnapshot(
                "owner@example.com",PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,ZoneId.of("America/New_York"));
        AlertTriggeredEvent.SensorSnapshot sensor = new AlertTriggeredEvent.SensorSnapshot(
                SENSOR_ID,type,"Living room","Window","Istanbul","Kadikoy",ZoneId.of("Europe/Istanbul"));
        return new AlertTriggeredEvent.Context(RULE_ID,recipient,sensor,RECORDED_AT,COOLDOWN_MINUTES);
    }
}