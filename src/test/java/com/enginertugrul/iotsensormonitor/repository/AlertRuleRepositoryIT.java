package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class AlertRuleRepositoryIT {

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @Test
    void listsOnlyTheOwnersRulesNewestFirstAndFetchesTheirSensors() {
        AppUser owner = persistUser("owner@example.com",false);
        AppUser otherOwner = persistUser("other@example.com",true);

        Sensor temperature = persistSensor(owner,SensorType.TEMPERATURE,"Temperature");
        Sensor humidity = persistSensor(owner,SensorType.HUMIDITY,"Humidity");
        Sensor motion = persistSensor(owner,SensorType.MOTION,"Motion");
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign");

        Long middleId = insertRule(humidity,true,CREATED_AT.plusSeconds(60));
        Long oldestId = insertRule(motion,false,CREATED_AT);
        insertRule(foreign,true,CREATED_AT.plusSeconds(180));
        Long newestId = insertRule(temperature,true,CREATED_AT.plusSeconds(120));
        flushAndClear();

        List<AlertRule> rules = alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId());

        assertThat(rules).extracting(AlertRule::getId).containsExactly(newestId,middleId,oldestId);
        assertThat(rules).extracting(AlertRule::isEnabled).containsExactly(true,true,false);
        assertThat(rules).allSatisfy(rule ->
                assertThat(entityManager.getEntityManagerFactory().getPersistenceUnitUtil().isLoaded(rule.getSensor())).isTrue());

        entityManager.clear();

        assertThat(rules)
                .extracting(rule -> rule.getSensor().getName())
                .containsExactly("Temperature","Humidity","Motion");
        assertThat(alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(-1L)).isEmpty();
    }



    @Test
    void findsRulesOnlyForTheirOwnerIncludingDisabledRules() {
        AppUser owner = persistUser("owner@example.com",false);
        AppUser otherOwner = persistUser("other@example.com",true);
        Sensor ownedSensor = persistSensor(owner,SensorType.TEMPERATURE,"Owned");
        Sensor foreignSensor = persistSensor(otherOwner,SensorType.MOTION,"Foreign");

        Long ownedRuleId = insertRule(ownedSensor,false,CREATED_AT);
        Long foreignRuleId = insertRule(foreignSensor,true,CREATED_AT);
        flushAndClear();

        assertThat(alertRuleRepository.findByIdAndOwnerId(ownedRuleId,owner.getId()).map(AlertRule::getId)).contains(ownedRuleId);
        assertThat(alertRuleRepository.findByIdAndOwnerId(foreignRuleId,otherOwner.getId()).map(AlertRule::getId)).contains(foreignRuleId);
        assertThat(alertRuleRepository.findByIdAndOwnerId(foreignRuleId,owner.getId())).isEmpty();
        assertThat(alertRuleRepository.findByIdAndOwnerId(ownedRuleId,otherOwner.getId())).isEmpty();
        assertThat(alertRuleRepository.findByIdAndOwnerId(-1L,owner.getId())).isEmpty();
        assertThat(alertRuleRepository.findByIdAndOwnerId(ownedRuleId,-1L)).isEmpty();
    }



    @Test
    void returnsOnlyEnabledEvaluationRulesForTheRequestedSensorInIdOrder() {
        AppUser owner = persistUser("owner@example.com",true);
        AppUser otherOwner = persistUser("other@example.com",true);
        Sensor target = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor sibling = persistSensor(owner,SensorType.HUMIDITY,"Sibling");
        Sensor foreign = persistSensor(otherOwner,SensorType.MOTION,"Foreign");

        Long firstId = insertRule(target,true,CREATED_AT.plusSeconds(60));
        insertRule(target,false,CREATED_AT.plusSeconds(30));
        Long secondId = insertRule(target,true,CREATED_AT.plusSeconds(120));
        Long thirdId = insertRule(target,true,CREATED_AT);
        insertRule(sibling,true,CREATED_AT);
        insertRule(foreign,true,CREATED_AT);
        flushAndClear();

        assertThat(alertRuleRepository.findEnabledForEvaluationBySensorId(target.getId()))
                .extracting(AlertRule::getId)
                .containsExactly(firstId,secondId,thirdId);
        assertThat(alertRuleRepository.findEnabledForEvaluationBySensorId(-1L)).isEmpty();
    }



    @Test
    void excludesEnabledRulesUntilTheirOwnerIsVerified() {
        AppUser owner = persistUser("owner@example.com",false);
        Sensor sensor = persistSensor(owner,SensorType.MOTION,"Motion");

        Long enabledRuleId = insertRule(sensor,true,CREATED_AT);
        insertRule(sensor,false,CREATED_AT.plusSeconds(30));
        flushAndClear();

        assertThat(alertRuleRepository.findEnabledForEvaluationBySensorId(sensor.getId())).isEmpty();

        AppUser reloadedOwner = appUserRepository.findById(owner.getId()).orElseThrow();
        reloadedOwner.verifyEmail(UPDATED_AT);
        flushAndClear();

        assertThat(alertRuleRepository.findEnabledForEvaluationBySensorId(sensor.getId()))
                .extracting(AlertRule::getId)
                .containsExactly(enabledRuleId);
    }



    private AppUser persistUser(String email,boolean verified) {
        AppUser user = new AppUser(email,"test-password-hash",CREATED_AT);

        if (verified) {
            user.verifyEmail(UPDATED_AT);
        }

        return appUserRepository.saveAndFlush(user);
    }



    private Sensor persistSensor(AppUser owner,SensorType type,String name) {
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT));
    }



    private Long insertRule(Sensor sensor,boolean enabled,Instant createdAt) {
        boolean motion = sensor.getType() == SensorType.MOTION;
        String unit = motion ? null : sensor.getType() == SensorType.TEMPERATURE ? "C" : "PERCENT";
        Timestamp timestamp = Timestamp.from(createdAt);

        return jdbcTemplate.queryForObject("""
                INSERT INTO alert_rules (
                    owner_id,sensor_id,rule_type,comparison_operator,
                    threshold_value,threshold_unit,event_type,enabled,
                    cooldown_minutes,created_at,updated_at
                )
                VALUES (?,?,?,?,?,?,?,?,60,?,?)
                RETURNING id
                """,Long.class,sensor.getOwner().getId(),sensor.getId(),
                motion ? "EVENT_DETECTED" : "NUMERIC_THRESHOLD",
                motion ? null : "ABOVE",motion ? null : 20.0,unit,
                motion ? "MOTION_DETECTED" : null,enabled,timestamp,timestamp);
    }



    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}