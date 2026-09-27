package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.alert.MotionEventAlertRuleForm;
import com.enginertugrul.iotsensormonitor.dto.alert.NumericThresholdAlertRuleForm;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertEventType;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.AlertRuleNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.InvalidAlertRuleException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AlertRuleRepository;
import com.enginertugrul.iotsensormonitor.service.sensor.SensorService;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertRuleServiceImplTest {

    private static final Long OWNER_ID = 42L;
    private static final Long SENSOR_ID = 100L;
    private static final Long RULE_ID = 200L;
    private static final int COOLDOWN_MINUTES = 37;

    @Mock
    private AlertRuleRepository alertRuleRepository;

    @Mock
    private SensorService sensorService;

    private AlertRuleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AlertRuleServiceImpl(alertRuleRepository,sensorService,new TemperatureUnitConverter());
    }



    @ParameterizedTest
    @CsvSource(value = {
            "TEMPERATURE,CELSIUS,20.0,20.0,C,ABOVE",
            "TEMPERATURE,FAHRENHEIT,68.0,20.0,C,BELOW",
            "TEMPERATURE,KELVIN,293.15,20.0,C,ABOVE",
            "TEMPERATURE,NULL,20.0,20.0,C,BELOW",
            "TEMPERATURE,CELSIUS,-273.15,-273.15,C,ABOVE",
            "TEMPERATURE,KELVIN,0.0,-273.15,C,BELOW",
            "HUMIDITY,KELVIN,0.0,0.0,PERCENT,ABOVE",
            "HUMIDITY,FAHRENHEIT,45.5,45.5,PERCENT,BELOW",
            "HUMIDITY,KELVIN,100.0,100.0,PERCENT,ABOVE"
    },nullValues = "NULL")
    void createsNumericRuleWithOwnedSensorAndCanonicalThreshold(SensorType type,TemperatureUnit preferredUnit,double submitted,double expected,MeasurementUnit canonicalUnit,ComparisonOperator operator) {
        Sensor sensor = ownedSensor(type,SENSOR_ID);
        NumericThresholdAlertRuleForm form = numericForm(SENSOR_ID,operator,submitted);
        when(sensorService.getSensorForUser(SENSOR_ID,OWNER_ID)).thenReturn(sensor);

        service.createNumericThresholdRule(OWNER_ID,form,preferredUnit);

        AlertRule saved = capturedSavedRule();
        assertThat(saved.getOwner()).isSameAs(sensor.getOwner());
        assertThat(saved.getSensor()).isSameAs(sensor);
        assertThat(saved.getRuleType()).isEqualTo(AlertRuleType.NUMERIC_THRESHOLD);
        assertThat(saved.getComparisonOperator()).isEqualTo(operator);
        assertThat(saved.getThresholdValue()).isCloseTo(expected,within(1.0e-9));
        assertThat(saved.getThresholdUnit()).isEqualTo(canonicalUnit);
        assertThat(saved.getEventType()).isNull();
        assertThat(saved.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getLastTriggeredAt()).isNull();
        assertThat(form.getThresholdValue()).isEqualTo(submitted);
        verify(sensorService).getSensorForUser(SENSOR_ID,OWNER_ID);
    }



    @Test
    void createsMotionRuleWithOwnedSensorAndNoThresholdFields() {
        Sensor sensor = ownedSensor(SensorType.MOTION,SENSOR_ID);
        when(sensorService.getSensorForUser(SENSOR_ID,OWNER_ID)).thenReturn(sensor);

        service.createMotionDetectedRule(OWNER_ID,motionForm(SENSOR_ID));

        AlertRule saved = capturedSavedRule();
        assertThat(saved.getOwner()).isSameAs(sensor.getOwner());
        assertThat(saved.getSensor()).isSameAs(sensor);
        assertThat(saved.getRuleType()).isEqualTo(AlertRuleType.EVENT_DETECTED);
        assertThat(saved.getEventType()).isEqualTo(AlertEventType.MOTION_DETECTED);
        assertThat(saved.getComparisonOperator()).isNull();
        assertThat(saved.getThresholdValue()).isNull();
        assertThat(saved.getThresholdUnit()).isNull();
        assertThat(saved.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.getLastTriggeredAt()).isNull();
        verify(sensorService).getSensorForUser(SENSOR_ID,OWNER_ID);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "TEMPERATURE,CELSIUS,NULL",
            "TEMPERATURE,CELSIUS,NaN",
            "TEMPERATURE,CELSIUS,Infinity",
            "TEMPERATURE,CELSIUS,-Infinity",
            "TEMPERATURE,CELSIUS,-273.16",
            "TEMPERATURE,FAHRENHEIT,-500.0",
            "TEMPERATURE,KELVIN,-0.01",
            "HUMIDITY,KELVIN,NULL",
            "HUMIDITY,KELVIN,NaN",
            "HUMIDITY,KELVIN,Infinity",
            "HUMIDITY,KELVIN,-Infinity",
            "HUMIDITY,KELVIN,-0.01",
            "HUMIDITY,KELVIN,100.01"
    },nullValues = "NULL")
    void rejectsInvalidCanonicalThresholdWithoutSaving(SensorType type,TemperatureUnit preferredUnit,Double threshold) {
        Sensor sensor = ownedSensor(type,SENSOR_ID);
        when(sensorService.getSensorForUser(SENSOR_ID,OWNER_ID)).thenReturn(sensor);

        assertThatThrownBy(() -> service.createNumericThresholdRule(OWNER_ID,numericForm(SENSOR_ID,ComparisonOperator.ABOVE,threshold),preferredUnit))
                .isExactlyInstanceOf(InvalidAlertRuleException.class)
                .hasMessage("Threshold value is invalid for the selected sensor")
                .hasCauseInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(alertRuleRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "MOTION,false",
            "TEMPERATURE,true",
            "HUMIDITY,true"
    })
    void rejectsIncompatibleRuleAndSensorTypes(SensorType type,boolean motionRule) {
        Sensor sensor = ownedSensor(type,SENSOR_ID);
        when(sensorService.getSensorForUser(SENSOR_ID,OWNER_ID)).thenReturn(sensor);
        String expectedMessage = motionRule
                ? "Motion-detected rules require a motion sensor"
                : "Numeric threshold rules require a numeric sensor";

        assertThatThrownBy(() -> createRule(motionRule,SENSOR_ID))
                .isExactlyInstanceOf(InvalidAlertRuleException.class)
                .hasMessage(expectedMessage)
                .hasNoCause();

        verifyNoInteractions(alertRuleRepository);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void propagatesOwnedSensorLookupFailureWithoutSaving(boolean motionRule) {
        SensorNotFoundException failure = new SensorNotFoundException();
        when(sensorService.getSensorForUser(SENSOR_ID,OWNER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> createRule(motionRule,SENSOR_ID)).isSameAs(failure);

        verify(sensorService).getSensorForUser(SENSOR_ID,OWNER_ID);
        verifyNoInteractions(alertRuleRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "true,false",
            "false,true",
            "true,true",
            "false,false"
    })
    void appliesRequestedEnabledStateToOwnedRule(boolean initiallyEnabled,boolean requestedEnabled) {
        AlertRule rule = AlertRule.motionDetected(ownedSensor(SensorType.MOTION,SENSOR_ID),COOLDOWN_MINUTES);
        if (!initiallyEnabled) {
            rule.disable();
        }
        when(alertRuleRepository.findByIdAndOwnerId(RULE_ID,OWNER_ID)).thenReturn(Optional.of(rule));

        service.setAlertRuleEnabled(OWNER_ID,RULE_ID,requestedEnabled);

        assertThat(rule.isEnabled()).isEqualTo(requestedEnabled);
        assertThat(rule.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        assertThat(rule.getEventType()).isEqualTo(AlertEventType.MOTION_DETECTED);
        verify(alertRuleRepository).findByIdAndOwnerId(RULE_ID,OWNER_ID);
        verifyNoInteractions(sensorService);
    }



    @Test
    void deletesTheRuleReturnedByOwnedLookup() {
        AlertRule rule = AlertRule.motionDetected(ownedSensor(SensorType.MOTION,SENSOR_ID),COOLDOWN_MINUTES);
        when(alertRuleRepository.findByIdAndOwnerId(RULE_ID,OWNER_ID)).thenReturn(Optional.of(rule));

        service.deleteAlertRule(OWNER_ID,RULE_ID);

        verify(alertRuleRepository).findByIdAndOwnerId(RULE_ID,OWNER_ID);
        verify(alertRuleRepository).delete(rule);
        verifyNoInteractions(sensorService);
    }



    @ParameterizedTest
    @ValueSource(strings = {"enable","disable","delete"})
    void rejectsRuleChangesWhenOwnedLookupIsEmpty(String action) {
        when(alertRuleRepository.findByIdAndOwnerId(RULE_ID,OWNER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> changeRule(action,RULE_ID))
                .isExactlyInstanceOf(AlertRuleNotFoundException.class)
                .hasMessage("Alert rule not found")
                .hasNoCause();

        verify(alertRuleRepository).findByIdAndOwnerId(RULE_ID,OWNER_ID);
        verifyNoMoreInteractions(alertRuleRepository);
        verifyNoInteractions(sensorService);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "CELSIUS,20.0",
            "FAHRENHEIT,68.0",
            "KELVIN,293.15",
            "NULL,20.0"
    },nullValues = "NULL")
    void mapsRuleDetailsInRepositoryOrderAndConvertsOnlyTemperature(TemperatureUnit preferredUnit,double expectedTemperature) {
        Sensor temperature = ownedSensor(SensorType.TEMPERATURE,101L);
        Sensor humidity = ownedSensor(SensorType.HUMIDITY,102L);
        Sensor motion = ownedSensor(SensorType.MOTION,103L);
        temperature.deactivate(TestFixtures.UPDATED_AT);

        AlertRule temperatureRule = identifiedRule(AlertRule.numericThreshold(temperature,ComparisonOperator.ABOVE,20.0,37),201L);
        AlertRule humidityRule = identifiedRule(AlertRule.numericThreshold(humidity,ComparisonOperator.BELOW,45.5,17),202L);
        AlertRule motionRule = identifiedRule(AlertRule.motionDetected(motion,29),203L);
        motionRule.disable();

        when(alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER_ID))
                .thenReturn(List.of(motionRule,temperatureRule,humidityRule));

        List<AlertRuleListItemDTO> result = service.getAlertRulesForUser(OWNER_ID,preferredUnit);

        assertThat(result).containsExactly(
                new AlertRuleListItemDTO(203L,103L,"Living room","Window",SensorType.MOTION,true,AlertRuleType.EVENT_DETECTED,null,AlertEventType.MOTION_DETECTED,null,29,false),
                new AlertRuleListItemDTO(201L,101L,"Living room","Window",SensorType.TEMPERATURE,false,AlertRuleType.NUMERIC_THRESHOLD,ComparisonOperator.ABOVE,null,expectedTemperature,37,true),
                new AlertRuleListItemDTO(202L,102L,"Living room","Window",SensorType.HUMIDITY,true,AlertRuleType.NUMERIC_THRESHOLD,ComparisonOperator.BELOW,null,45.5,17,true));
        assertThat(temperatureRule.getThresholdValue()).isEqualTo(20.0);
        assertThat(temperatureRule.getThresholdUnit()).isEqualTo(MeasurementUnit.C);
        assertThat(humidityRule.getThresholdValue()).isEqualTo(45.5);
        verify(alertRuleRepository).findByOwnerIdOrderByCreatedAtDesc(OWNER_ID);
        verifyNoInteractions(sensorService);
    }



    @Test
    void returnsEmptyListWhenOwnerHasNoRules() {
        when(alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(List.of());

        assertThat(service.getAlertRulesForUser(OWNER_ID,TemperatureUnit.CELSIUS)).isEmpty();

        verify(alertRuleRepository).findByOwnerIdOrderByCreatedAtDesc(OWNER_ID);
        verifyNoInteractions(sensorService);
    }



    private AlertRule capturedSavedRule() {
        ArgumentCaptor<AlertRule> captor = ArgumentCaptor.forClass(AlertRule.class);
        verify(alertRuleRepository).save(captor.capture());
        return captor.getValue();
    }



    private static Sensor ownedSensor(SensorType type,Long sensorId) {
        Sensor sensor = TestFixtures.sensor(type);
        ReflectionTestUtils.setField(sensor,"id",sensorId);
        ReflectionTestUtils.setField(sensor.getOwner(),"id",OWNER_ID);
        return sensor;
    }



    private static AlertRule identifiedRule(AlertRule rule,Long ruleId) {
        ReflectionTestUtils.setField(rule,"id",ruleId);
        return rule;
    }



    private static NumericThresholdAlertRuleForm numericForm(Long sensorId,ComparisonOperator operator,Double threshold) {
        NumericThresholdAlertRuleForm form = new NumericThresholdAlertRuleForm();
        form.setSensorId(sensorId);
        form.setComparisonOperator(operator);
        form.setThresholdValue(threshold);
        form.setCooldownMinutes(COOLDOWN_MINUTES);
        return form;
    }



    private static MotionEventAlertRuleForm motionForm(Long sensorId) {
        MotionEventAlertRuleForm form = new MotionEventAlertRuleForm();
        form.setSensorId(sensorId);
        form.setCooldownMinutes(COOLDOWN_MINUTES);
        return form;
    }



    private void createRule(boolean motionRule,Long sensorId) {
        if (motionRule) {
            service.createMotionDetectedRule(OWNER_ID,motionForm(sensorId));
        } else {
            service.createNumericThresholdRule(OWNER_ID,numericForm(sensorId,ComparisonOperator.ABOVE,20.0),TemperatureUnit.CELSIUS);
        }
    }



    private void changeRule(String action,Long ruleId) {
        switch (action) {
            case "enable" -> service.setAlertRuleEnabled(OWNER_ID,ruleId,true);
            case "disable" -> service.setAlertRuleEnabled(OWNER_ID,ruleId,false);
            case "delete" -> service.deleteAlertRule(OWNER_ID,ruleId);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }
}