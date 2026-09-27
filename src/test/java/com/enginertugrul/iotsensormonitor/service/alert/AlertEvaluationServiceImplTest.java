package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.AlertRuleRepository;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class AlertEvaluationServiceImplTest {

    private static final Long SENSOR_ID = 100L;
    private static final Long RULE_ID = 200L;
    private static final int COOLDOWN_MINUTES = 10;
    private static final Instant RECORDED_AT = TestFixtures.CREATED_AT.plusSeconds(3600).plusNanos(123456000);

    @Mock
    private AlertRuleRepository alertRuleRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private AlertTriggeredEventFactory eventFactory;

    @Mock
    private AlertTriggeredEvent event;

    private AlertEvaluationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlertEvaluationServiceImpl(alertRuleRepository,eventPublisher,eventFactory);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,ABOVE,25.0",
            "TEMPERATURE,BELOW,15.0",
            "HUMIDITY,ABOVE,70.0",
            "HUMIDITY,BELOW,40.0",
            "MOTION,,"
    })
    void marksMatchingRuleWithRecordedTimeBeforeCreatingAndPublishingEvent(SensorType type,ComparisonOperator operator,Double value) {
        Sensor sensor = identifiedSensor(type);
        AlertRule rule = rule(sensor,operator);
        SensorReading reading = reading(sensor,value,true);
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of(rule));
        when(eventFactory.from(rule,reading)).thenAnswer(invocation -> {
            assertThat(rule.getLastTriggeredAt()).isEqualTo(RECORDED_AT);
            return event;
        });

        service.evaluateReading(reading);

        assertThat(rule.getLastTriggeredAt()).isEqualTo(RECORDED_AT);
        verify(alertRuleRepository).findEnabledForEvaluationBySensorId(SENSOR_ID);
        InOrder order = inOrder(eventFactory,eventPublisher);
        order.verify(eventFactory).from(rule,reading);
        order.verify(eventPublisher).publishEvent(event);
        order.verifyNoMoreInteractions();
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,ABOVE,20.0",
            "TEMPERATURE,ABOVE,19.0",
            "TEMPERATURE,BELOW,20.0",
            "TEMPERATURE,BELOW,21.0",
            "HUMIDITY,ABOVE,50.0",
            "HUMIDITY,ABOVE,49.0",
            "HUMIDITY,BELOW,50.0",
            "HUMIDITY,BELOW,51.0",
            "MOTION,,"
    })
    void ignoresNonMatchingReadingsWithoutStartingCooldown(SensorType type,ComparisonOperator operator,Double value) {
        Sensor sensor = identifiedSensor(type);
        AlertRule rule = rule(sensor,operator);
        SensorReading reading = reading(sensor,value,false);
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of(rule));

        service.evaluateReading(reading);

        assertThat(rule.getLastTriggeredAt()).isNull();
        verifyNoInteractions(eventFactory,eventPublisher);
    }



    @ParameterizedTest
    @CsvSource({
            "-1,false",
            "0,false",
            "599999999999,false",
            "600000000000,true",
            "600000000001,true"
    })
    void usesRecordedTimeForOlderDuplicateAndCooldownBoundaryReadings(long nanosSinceLastTrigger,boolean expectedTrigger) {
        Sensor sensor = identifiedSensor(SensorType.TEMPERATURE);
        AlertRule rule = rule(sensor,ComparisonOperator.ABOVE);
        rule.markTriggered(RECORDED_AT);
        Instant nextRecordedAt = RECORDED_AT.plusNanos(nanosSinceLastTrigger);
        SensorReading reading = SensorReading.temperature(sensor,25.0,nextRecordedAt);
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of(rule));
        if (expectedTrigger) {
            when(eventFactory.from(rule,reading)).thenReturn(event);
        }

        service.evaluateReading(reading);

        if (expectedTrigger) {
            assertThat(rule.getLastTriggeredAt()).isEqualTo(nextRecordedAt);
            verify(eventFactory).from(rule,reading);
            verify(eventPublisher).publishEvent(event);
        } else {
            assertThat(rule.getLastTriggeredAt()).isEqualTo(RECORDED_AT);
            verifyNoInteractions(eventFactory,eventPublisher);
        }
    }



    @Test
    void suppressesDisabledMatchingRuleEvenIfRepositoryReturnsIt() {
        Sensor sensor = identifiedSensor(SensorType.MOTION);
        AlertRule rule = rule(sensor,null);
        rule.disable();
        SensorReading reading = SensorReading.motion(sensor,true,RECORDED_AT);
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of(rule));

        service.evaluateReading(reading);

        assertThat(rule.getLastTriggeredAt()).isNull();
        verifyNoInteractions(eventFactory,eventPublisher);
    }



    @Test
    void doesNothingWhenSensorHasNoEligibleRules() {
        SensorReading reading = SensorReading.temperature(identifiedSensor(SensorType.TEMPERATURE),25.0,RECORDED_AT);
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of());

        service.evaluateReading(reading);

        verify(alertRuleRepository).findEnabledForEvaluationBySensorId(SENSOR_ID);
        verifyNoInteractions(eventFactory,eventPublisher);
    }



    @Test
    void rejectsNullReadingBeforeAccessingDependencies() {
        assertThatThrownBy(() -> service.evaluateReading(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("reading must not be null");

        verifyNoInteractions(alertRuleRepository,eventFactory,eventPublisher);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void propagatesSnapshotAndPublicationFailuresToTheCaller(boolean publicationFailure) {
        Sensor sensor = identifiedSensor(SensorType.TEMPERATURE);
        AlertRule rule = rule(sensor,ComparisonOperator.ABOVE);
        SensorReading reading = SensorReading.temperature(sensor,25.0,RECORDED_AT);
        IllegalStateException failure = new IllegalStateException("Alert processing failed");
        when(alertRuleRepository.findEnabledForEvaluationBySensorId(SENSOR_ID)).thenReturn(List.of(rule));

        if (publicationFailure) {
            when(eventFactory.from(rule,reading)).thenReturn(event);
            doThrow(failure).when(eventPublisher).publishEvent(event);
        } else {
            when(eventFactory.from(rule,reading)).thenThrow(failure);
        }

        assertThatThrownBy(() -> service.evaluateReading(reading)).isSameAs(failure);

        verify(eventFactory).from(rule,reading);
        if (publicationFailure) {
            verify(eventPublisher).publishEvent(event);
        } else {
            verifyNoInteractions(eventPublisher);
        }
    }



    private static Sensor identifiedSensor(SensorType type) {
        Sensor sensor = TestFixtures.sensor(type);
        ReflectionTestUtils.setField(sensor,"id",SENSOR_ID);
        return sensor;
    }



    private static AlertRule rule(Sensor sensor,ComparisonOperator operator) {
        AlertRule rule = switch (sensor.getType()) {
            case TEMPERATURE -> AlertRule.numericThreshold(sensor,operator,20.0,COOLDOWN_MINUTES);
            case HUMIDITY -> AlertRule.numericThreshold(sensor,operator,50.0,COOLDOWN_MINUTES);
            case MOTION -> AlertRule.motionDetected(sensor,COOLDOWN_MINUTES);
        };
        ReflectionTestUtils.setField(rule,"id",RULE_ID);
        return rule;
    }



    private static SensorReading reading(Sensor sensor,Double value,boolean motionDetected) {
        return switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,RECORDED_AT);
            case HUMIDITY -> SensorReading.humidity(sensor,value,RECORDED_AT);
            case MOTION -> SensorReading.motion(sensor,motionDetected,RECORDED_AT);
        };
    }
}