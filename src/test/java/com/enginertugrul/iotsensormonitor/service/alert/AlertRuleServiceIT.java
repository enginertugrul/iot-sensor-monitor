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
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.AlertRuleNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.InvalidAlertRuleException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AlertRuleRepository;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class AlertRuleServiceIT {

    private static final Instant CREATED_AT = TEST_INSTANT.minusSeconds(3600);
    private static final int COOLDOWN_MINUTES = 37;

    @Autowired
    private AlertRuleService service;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureOwnerIds = new ArrayList<>();


    @AfterEach
    void tearDown() {
        for (Long ownerId : fixtureOwnerIds) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
        }
    }



    @ParameterizedTest
    @CsvSource(value = {
            "TEMPERATURE,CELSIUS,20.0,20.0,C,ABOVE",
            "TEMPERATURE,FAHRENHEIT,68.0,20.0,C,BELOW",
            "TEMPERATURE,KELVIN,293.15,20.0,C,ABOVE",
            "TEMPERATURE,NULL,20.0,20.0,C,BELOW",
            "TEMPERATURE,KELVIN,0.0,-273.15,C,ABOVE",
            "HUMIDITY,KELVIN,0.0,0.0,PERCENT,BELOW",
            "HUMIDITY,FAHRENHEIT,45.5,45.5,PERCENT,ABOVE",
            "HUMIDITY,KELVIN,100.0,100.0,PERCENT,BELOW"
    },nullValues = "NULL")
    void commitsNumericRuleWithCanonicalThresholdAndDisplaysRequestedUnit(SensorType type,TemperatureUnit preferredUnit,double submitted,double expected,MeasurementUnit canonicalUnit,ComparisonOperator operator) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Numeric sensor");

        service.createNumericThresholdRule(owner.getId(),numericForm(sensor.getId(),operator,submitted),preferredUnit);

        AlertRule stored = onlyRule(owner.getId());
        assertThat(stored.getId()).isPositive();
        assertThat(stored.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(stored.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(stored.getRuleType()).isEqualTo(AlertRuleType.NUMERIC_THRESHOLD);
        assertThat(stored.getComparisonOperator()).isEqualTo(operator);
        assertThat(stored.getThresholdValue()).isCloseTo(expected,within(1.0e-9));
        assertThat(stored.getThresholdUnit()).isEqualTo(canonicalUnit);
        assertThat(stored.getEventType()).isNull();
        assertThat(stored.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getLastTriggeredAt()).isNull();

        List<AlertRuleListItemDTO> items = service.getAlertRulesForUser(owner.getId(),preferredUnit);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().id()).isEqualTo(stored.getId());
        assertThat(items.getFirst().thresholdValue()).isCloseTo(submitted,within(1.0e-9));

        AlertRule reloaded = reload(stored.getId());
        assertThat(reloaded.getThresholdValue()).isCloseTo(expected,within(1.0e-9));
        assertThat(reloaded.getThresholdUnit()).isEqualTo(canonicalUnit);
    }



    @Test
    void commitsMotionRuleWithOwnerAndEventShape() {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,SensorType.MOTION,"Motion sensor");

        service.createMotionDetectedRule(owner.getId(),motionForm(sensor.getId()));

        AlertRule stored = onlyRule(owner.getId());
        assertThat(stored.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(stored.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(stored.getRuleType()).isEqualTo(AlertRuleType.EVENT_DETECTED);
        assertThat(stored.getEventType()).isEqualTo(AlertEventType.MOTION_DETECTED);
        assertThat(stored.getComparisonOperator()).isNull();
        assertThat(stored.getThresholdValue()).isNull();
        assertThat(stored.getThresholdUnit()).isNull();
        assertThat(stored.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getLastTriggeredAt()).isNull();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void treatsMissingAndForeignSensorsIdenticallyDuringCreation(SensorType type) {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor foreign = persistSensor(otherOwner,type,"Foreign sensor");
        AlertRule existing = persistRule(foreign);

        for (Long sensorId : List.of(-1L,foreign.getId())) {
            assertThatThrownBy(() -> createRule(owner.getId(),sensorId,type == SensorType.MOTION))
                    .isExactlyInstanceOf(SensorNotFoundException.class)
                    .hasMessage("Sensor not found")
                    .hasNoCause();
        }

        assertThat(service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS)).isEmpty();
        assertThat(service.getAlertRulesForUser(otherOwner.getId(),TemperatureUnit.CELSIUS))
                .extracting(AlertRuleListItemDTO::id).containsExactly(existing.getId());
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void allowsRuleCreationForInactiveOwnedSensors(SensorType type) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Inactive sensor");
        sensor.deactivate(TEST_INSTANT);
        sensorRepository.saveAndFlush(sensor);

        createRule(owner.getId(),sensor.getId(),type == SensorType.MOTION);

        AlertRule stored = onlyRule(owner.getId());
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(sensorRepository.findById(sensor.getId()).orElseThrow().isActive()).isFalse();

        List<AlertRuleListItemDTO> items = service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS);
        assertThat(items).hasSize(1);
        assertThat(items.getFirst().sensorActive()).isFalse();
        assertThat(items.getFirst().enabled()).isTrue();
    }



    @ParameterizedTest
    @CsvSource({
            "MOTION,false",
            "TEMPERATURE,true",
            "HUMIDITY,true"
    })
    void rejectsIncompatibleSensorTypesWithoutPersistingRules(SensorType type,boolean motionRule) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Incompatible sensor");

        assertThatThrownBy(() -> createRule(owner.getId(),sensor.getId(),motionRule))
                .isExactlyInstanceOf(InvalidAlertRuleException.class)
                .hasNoCause();

        assertThat(alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId())).isEmpty();
        assertThat(sensorRepository.existsById(sensor.getId())).isTrue();
    }



    @ParameterizedTest
    @CsvSource(value = {
            "TEMPERATURE,CELSIUS,-273.16",
            "TEMPERATURE,FAHRENHEIT,-500.0",
            "TEMPERATURE,KELVIN,-0.01",
            "TEMPERATURE,CELSIUS,NaN",
            "TEMPERATURE,CELSIUS,NULL",
            "HUMIDITY,KELVIN,-0.01",
            "HUMIDITY,KELVIN,100.01",
            "HUMIDITY,KELVIN,Infinity",
            "HUMIDITY,KELVIN,-Infinity"
    },nullValues = "NULL")
    void rejectsInvalidThresholdWithoutAddingOrChangingExistingRules(SensorType type,TemperatureUnit preferredUnit,Double threshold) {
        AppUser owner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Numeric sensor");
        persistRule(sensor);
        List<AlertRuleListItemDTO> before = service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS);

        assertThatThrownBy(() -> service.createNumericThresholdRule(owner.getId(),numericForm(sensor.getId(),ComparisonOperator.ABOVE,threshold),preferredUnit))
                .isExactlyInstanceOf(InvalidAlertRuleException.class)
                .hasMessage("Threshold value is invalid for the selected sensor")
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS)).isEqualTo(before);
        assertThat(alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId())).hasSize(1);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void commitsRepeatedEnableAndDisableRequestsWithoutChangingOtherRules(SensorType type) {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Target sensor");
        AlertRule target = persistRule(sensor);
        AlertRule sibling = persistRule(persistSensor(owner,type,"Sibling sensor"));
        AlertRule foreign = persistRule(persistSensor(otherOwner,type,"Foreign sensor"));

        for (boolean enabled : new boolean[]{false,false,true,true}) {
            service.setAlertRuleEnabled(owner.getId(),target.getId(),enabled);

            AlertRule reloaded = reload(target.getId());
            assertThat(reloaded.isEnabled()).isEqualTo(enabled);
            assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());
            assertThat(reloaded.getSensor().getId()).isEqualTo(sensor.getId());
            assertThat(reloaded.getRuleType()).isEqualTo(target.getRuleType());
            assertThat(reloaded.getComparisonOperator()).isEqualTo(target.getComparisonOperator());
            assertThat(reloaded.getThresholdValue()).isEqualTo(target.getThresholdValue());
            assertThat(reloaded.getThresholdUnit()).isEqualTo(target.getThresholdUnit());
            assertThat(reloaded.getEventType()).isEqualTo(target.getEventType());
            assertThat(reloaded.getCooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);
        }

        assertThat(reload(sibling.getId()).isEnabled()).isTrue();
        assertThat(reload(foreign.getId()).isEnabled()).isTrue();
        assertThat(sensorRepository.findById(sensor.getId()).orElseThrow().isActive()).isTrue();
    }



    @ParameterizedTest
    @ValueSource(strings = {"enable","disable","delete"})
    void treatsMissingAndForeignRulesIdenticallyDuringChanges(String action) {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor foreignSensor = persistSensor(otherOwner,SensorType.MOTION,"Foreign sensor");
        AlertRule foreignRule = persistRule(foreignSensor);
        if ("enable".equals(action)) {
            service.setAlertRuleEnabled(otherOwner.getId(),foreignRule.getId(),false);
        }
        List<AlertRuleListItemDTO> before = service.getAlertRulesForUser(otherOwner.getId(),TemperatureUnit.CELSIUS);

        for (Long ruleId : List.of(-1L,foreignRule.getId())) {
            assertThatThrownBy(() -> changeRule(action,owner.getId(),ruleId))
                    .isExactlyInstanceOf(AlertRuleNotFoundException.class)
                    .hasMessage("Alert rule not found")
                    .hasNoCause();
        }

        assertThat(service.getAlertRulesForUser(otherOwner.getId(),TemperatureUnit.CELSIUS)).isEqualTo(before);
        assertThat(service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS)).isEmpty();
        assertThat(sensorRepository.existsById(foreignSensor.getId())).isTrue();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void deletesOnlySelectedOwnedRuleAndPreservesItsSensor(SensorType type) {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        Sensor sensor = persistSensor(owner,type,"Target sensor");
        AlertRule target = persistRule(sensor);
        AlertRule sibling = persistRule(persistSensor(owner,type,"Sibling sensor"));
        AlertRule foreign = persistRule(persistSensor(otherOwner,type,"Foreign sensor"));

        service.deleteAlertRule(owner.getId(),target.getId());

        assertThat(alertRuleRepository.existsById(target.getId())).isFalse();
        assertThat(alertRuleRepository.existsById(sibling.getId())).isTrue();
        assertThat(alertRuleRepository.existsById(foreign.getId())).isTrue();
        assertThat(sensorRepository.existsById(sensor.getId())).isTrue();
        assertThat(service.getAlertRulesForUser(owner.getId(),TemperatureUnit.CELSIUS))
                .extracting(AlertRuleListItemDTO::id).containsExactly(sibling.getId());
        assertThat(service.getAlertRulesForUser(otherOwner.getId(),TemperatureUnit.CELSIUS))
                .extracting(AlertRuleListItemDTO::id).containsExactly(foreign.getId());
    }



    @Test
    void listsOnlyOwnedRulesNewestFirstWithConvertedTemperature() {
        AppUser owner = persistUser();
        AppUser otherOwner = persistUser();
        AlertRule temperature = persistRule(persistSensor(owner,SensorType.TEMPERATURE,"Temperature"));
        AlertRule humidity = persistRule(persistSensor(owner,SensorType.HUMIDITY,"Humidity"));
        persistRule(persistSensor(otherOwner,SensorType.MOTION,"Foreign motion"));

        jdbcTemplate.update("UPDATE alert_rules SET created_at=? WHERE id=?",Timestamp.from(TEST_INSTANT.minusSeconds(60)),temperature.getId());
        jdbcTemplate.update("UPDATE alert_rules SET created_at=? WHERE id=?",Timestamp.from(TEST_INSTANT),humidity.getId());

        List<AlertRuleListItemDTO> items = service.getAlertRulesForUser(owner.getId(),TemperatureUnit.FAHRENHEIT);

        assertThat(items).extracting(AlertRuleListItemDTO::id).containsExactly(humidity.getId(),temperature.getId());
        assertThat(items.get(0).sensorName()).isEqualTo("Humidity");
        assertThat(items.get(0).thresholdValue()).isEqualTo(45.5);
        assertThat(items.get(1).sensorName()).isEqualTo("Temperature");
        assertThat(items.get(1).thresholdValue()).isCloseTo(68.0,within(1.0e-9));
        assertThat(reload(temperature.getId()).getThresholdValue()).isEqualTo(20.0);
    }



    private AppUser persistUser() {
        AppUser user = new AppUser("alert-rules-" + UUID.randomUUID() + "@example.com","test-password-hash",CREATED_AT);
        AppUser saved = appUserRepository.saveAndFlush(user);
        fixtureOwnerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser owner,SensorType type,String name) {
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT));
    }



    private AlertRule persistRule(Sensor sensor) {
        AlertRule rule = switch (sensor.getType()) {
            case TEMPERATURE -> AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,20.0,COOLDOWN_MINUTES);
            case HUMIDITY -> AlertRule.numericThreshold(sensor,ComparisonOperator.BELOW,45.5,COOLDOWN_MINUTES);
            case MOTION -> AlertRule.motionDetected(sensor,COOLDOWN_MINUTES);
        };
        return alertRuleRepository.saveAndFlush(rule);
    }



    private AlertRule onlyRule(Long ownerId) {
        List<AlertRule> rules = alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId);
        assertThat(rules).hasSize(1);
        return rules.getFirst();
    }



    private AlertRule reload(Long ruleId) {
        return alertRuleRepository.findById(ruleId).orElseThrow();
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



    private void createRule(Long ownerId,Long sensorId,boolean motionRule) {
        if (motionRule) {
            service.createMotionDetectedRule(ownerId,motionForm(sensorId));
        } else {
            service.createNumericThresholdRule(ownerId,numericForm(sensorId,ComparisonOperator.ABOVE,20.0),TemperatureUnit.CELSIUS);
        }
    }



    private void changeRule(String action,Long ownerId,Long ruleId) {
        switch (action) {
            case "enable" -> service.setAlertRuleEnabled(ownerId,ruleId,true);
            case "disable" -> service.setAlertRuleEnabled(ownerId,ruleId,false);
            case "delete" -> service.deleteAlertRule(ownerId,ruleId);
            default -> throw new IllegalArgumentException("Unknown action: " + action);
        }
    }
}