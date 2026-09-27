package com.enginertugrul.iotsensormonitor.service.sensor;

import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorCreateForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.DuplicateSensorNameException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorTimezoneLockedException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Map;
import java.util.UUID;



import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
class SensorServiceIT {

    private static final Instant SENSOR_CREATED_AT = TEST_INSTANT.minusSeconds(172800);
    private static final Instant BUCKET_START = Instant.parse("2026-01-14T00:00:00Z");
    private static final Map<String,Long> DEPENDENT_COUNTS = Map.of(
            "sensor_readings",1L,
            "alert_rules",1L,
            "hourly_sensor_summaries",1L,
            "daily_sensor_summaries",1L,
            "sensor_rollup_checkpoints",2L);

    @Autowired
    private SensorService sensorService;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorIngestionTokenGenerator tokenGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> fixtureOwnerIds = new ArrayList<>();

    private AppUser owner;
    private AppUser otherOwner;


    @BeforeEach
    void setUp() {
        owner = persistUser();
        otherOwner = persistUser();
    }


    @AfterEach
    void tearDown() {
        for (Long ownerId : fixtureOwnerIds) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
        }
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void commitsSensorWithNormalizedDetailsAndOnlyTheTokenHash(SensorType type) {
        SensorCreateForm form = createForm(" Living room ");
        form.setType(type);
        form.setTimezone(" Europe/Istanbul ");

        CreatedSensorDTO created = sensorService.createSensor(owner.getId(),form);
        Sensor reloaded = reload(created.sensorId());

        assertThat(created.sensorId()).isPositive();
        assertThat(created.sensorName()).isEqualTo("Living room");
        assertThat(created.rawIngestionToken()).matches("[A-Za-z0-9_-]{43}");
        assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(reloaded.getType()).isEqualTo(type);
        assertThat(reloaded.getName()).isEqualTo("Living room");
        assertThat(reloaded.getCity()).isEqualTo("Istanbul");
        assertThat(reloaded.getDistrict()).isEqualTo("Kadikoy");
        assertThat(reloaded.getInstallationLocation()).isEqualTo("Window");
        assertThat(reloaded.getTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(reloaded.isActive()).isTrue();
        assertThat(reloaded.getFirstReadingAt()).isNull();
        assertThat(reloaded.getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getIngestionTokenHash())
                .isEqualTo(tokenGenerator.hash(created.rawIngestionToken()))
                .isNotEqualTo(created.rawIngestionToken());
    }



    @Test
    void rejectsCaseInsensitiveDuplicateForSameOwnerButAllowsAnotherOwner() {
        CreatedSensorDTO original = sensorService.createSensor(owner.getId(),createForm("Living room"));
        String originalHash = reload(original.sensorId()).getIngestionTokenHash();

        assertThatThrownBy(() -> sensorService.createSensor(owner.getId(),createForm(" LIVING ROOM ")))
                .isExactlyInstanceOf(DuplicateSensorNameException.class);

        CreatedSensorDTO other = sensorService.createSensor(otherOwner.getId(),createForm(" living room "));

        assertThat(other.sensorId()).isNotEqualTo(original.sensorId());
        assertThat(sensorService.getSensorsForUser(owner.getId()))
                .extracting(SensorListItemDTO::id).containsExactly(original.sensorId());
        assertThat(sensorService.getSensorsForUser(otherOwner.getId()))
                .extracting(SensorListItemDTO::id).containsExactly(other.sensorId());
        assertThat(reload(original.sensorId()).getIngestionTokenHash()).isEqualTo(originalHash);
        assertThat(reload(original.sensorId()).getName()).isEqualTo("Living room");
    }



    @Test
    void rejectsRenameToAnotherOwnedSensorNameWithoutChangingEitherSensor() {
        Sensor first = persistSensor(owner,"Living room");
        Sensor second = persistSensor(owner,"Bedroom");
        SensorUpdateForm before = sensorService.getSensorUpdateForm(first.getId(),owner.getId());

        assertThatThrownBy(() -> sensorService.updateSensor(first.getId(),owner.getId(),updateForm(" BEDROOM ","Europe/Istanbul")))
                .isExactlyInstanceOf(DuplicateSensorNameException.class);

        assertThat(sensorService.getSensorUpdateForm(first.getId(),owner.getId()))
                .usingRecursiveComparison().isEqualTo(before);
        assertThat(reload(first.getId()).getUpdatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(reload(second.getId()).getName()).isEqualTo("Bedroom");
        assertThat(reload(second.getId()).getUpdatedAt()).isEqualTo(SENSOR_CREATED_AT);
    }



    @Test
    void allowsCaseOnlyRenameOfTheSameSensor() {
        Sensor sensor = persistSensor(owner,"Living room");

        sensorService.updateSensor(sensor.getId(),owner.getId(),updateForm(" LIVING ROOM ","UTC"));

        assertThat(reload(sensor.getId()).getName()).isEqualTo("LIVING ROOM");
        assertThat(sensorService.getSensorsForUser(owner.getId()))
                .extracting(SensorListItemDTO::id).containsExactly(sensor.getId());
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void commitsDetailUpdatesWithPermittedTimezoneAndPreservesTokenAndHistory(boolean hasHistory) {
        Sensor sensor = persistSensor(owner,"Living room");
        Instant firstReadingAt = SENSOR_CREATED_AT.plusSeconds(30);
        if (hasHistory) {
            sensor.recordFirstReading(firstReadingAt,firstReadingAt);
            sensorRepository.saveAndFlush(sensor);
        }
        String timezone = hasHistory ? "UTC" : "Europe/Istanbul";
        String originalHash = sensor.getIngestionTokenHash();

        sensorService.updateSensor(sensor.getId(),owner.getId(),updateForm(" Renamed sensor "," " + timezone + " "));

        Sensor reloaded = reload(sensor.getId());
        assertThat(reloaded.getName()).isEqualTo("Renamed sensor");
        assertThat(reloaded.getCity()).isEqualTo("Ankara");
        assertThat(reloaded.getDistrict()).isEqualTo("Cankaya");
        assertThat(reloaded.getInstallationLocation()).isEqualTo("Shelf");
        assertThat(reloaded.getTimezone()).isEqualTo(timezone);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(reloaded.getType()).isEqualTo(SensorType.TEMPERATURE);
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(originalHash);
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(hasHistory ? firstReadingAt : null);
        assertThat(reloaded.isActive()).isTrue();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void keepsTimezoneLockedEvenAfterRawReadingsAreRemoved(boolean removeRawReadings) {
        Sensor sensor = persistSensor(owner,"Living room");
        Instant firstReadingAt = SENSOR_CREATED_AT.plusSeconds(60);
        Timestamp recordedAt = Timestamp.from(firstReadingAt);

        jdbcTemplate.update("INSERT INTO sensor_readings (sensor_id,numeric_value,unit,recorded_at) VALUES (?,20.0,'C',?)",sensor.getId(),recordedAt);
        jdbcTemplate.update("UPDATE sensors SET first_reading_at=?,updated_at=? WHERE id=?",recordedAt,recordedAt,sensor.getId());

        if (removeRawReadings) {
            jdbcTemplate.update("DELETE FROM sensor_readings WHERE sensor_id=?",sensor.getId());
        }

        SensorUpdateForm before = sensorService.getSensorUpdateForm(sensor.getId(),owner.getId());

        assertThatThrownBy(() -> sensorService.updateSensor(sensor.getId(),owner.getId(),updateForm("Changed","Europe/Istanbul")))
                .isExactlyInstanceOf(SensorTimezoneLockedException.class);

        assertThat(sensorService.getSensorUpdateForm(sensor.getId(),owner.getId()))
                .usingRecursiveComparison().isEqualTo(before);

        Sensor reloaded = reload(sensor.getId());
        assertThat(reloaded.hasRecordedReadings()).isTrue();
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(firstReadingAt);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(firstReadingAt);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM sensor_readings WHERE sensor_id=?",Long.class,sensor.getId()))
                .isEqualTo(removeRawReadings ? 0L : 1L);
    }



    @Test
    void rollsBackEarlierFieldMutationWhenLaterDetailValidationFails() {
        Sensor sensor = persistSensor(owner,"Living room");
        SensorUpdateForm before = sensorService.getSensorUpdateForm(sensor.getId(),owner.getId());
        SensorUpdateForm invalid = updateForm("Changed before city validation","UTC");
        invalid.setCity(" ");

        assertThatThrownBy(() -> sensorService.updateSensor(sensor.getId(),owner.getId(),invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("city must not be blank");

        assertThat(sensorService.getSensorUpdateForm(sensor.getId(),owner.getId()))
                .usingRecursiveComparison().isEqualTo(before);
        assertThat(reload(sensor.getId()).getUpdatedAt()).isEqualTo(SENSOR_CREATED_AT);
    }



    @ParameterizedTest
    @CsvSource({"true,true","true,false","false,true","false,false"})
    void commitsActivationChangesAndPreservesTimestampForNoOp(boolean initiallyActive,boolean requestedActive) {
        Sensor sensor = persistSensor(owner,"Living room");
        if (!initiallyActive) {
            sensor.deactivate(SENSOR_CREATED_AT);
            sensorRepository.saveAndFlush(sensor);
        }

        if (requestedActive) {
            sensorService.activateSensor(sensor.getId(),owner.getId());
        } else {
            sensorService.deactivateSensor(sensor.getId(),owner.getId());
        }

        Sensor reloaded = reload(sensor.getId());
        assertThat(reloaded.isActive()).isEqualTo(requestedActive);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(initiallyActive == requestedActive ? SENSOR_CREATED_AT : TEST_INSTANT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(sensor.getIngestionTokenHash());
    }



    @ParameterizedTest
    @ValueSource(strings = {"read","form","update","activate","deactivate","delete"})
    void treatsMissingAndForeignSensorsIdenticallyForEveryOwnedOperation(String operation) {
        Sensor foreign = persistSensor(otherOwner,"Foreign sensor");

        assertThatThrownBy(() -> invokeOwnedOperation(operation,-1L,owner.getId()))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThatThrownBy(() -> invokeOwnedOperation(operation,foreign.getId(),owner.getId()))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        Sensor unchanged = reload(foreign.getId());
        assertThat(unchanged.getOwner().getId()).isEqualTo(otherOwner.getId());
        assertThat(unchanged.getName()).isEqualTo("Foreign sensor");
        assertThat(unchanged.getCity()).isEqualTo("Istanbul");
        assertThat(unchanged.getTimezone()).isEqualTo("UTC");
        assertThat(unchanged.isActive()).isTrue();
        assertThat(unchanged.getUpdatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(unchanged.getIngestionTokenHash()).isEqualTo(foreign.getIngestionTokenHash());
    }



    @Test
    void listsOnlyRequestedOwnersSensorsInDescendingCreationOrder() {
        Sensor older = persistSensor(owner,"Older",SENSOR_CREATED_AT);
        Sensor newer = persistSensor(owner,"Newer",SENSOR_CREATED_AT.plusSeconds(60));
        Sensor foreign = persistSensor(otherOwner,"Foreign",SENSOR_CREATED_AT.plusSeconds(120));

        assertThat(sensorService.getSensorsForUser(owner.getId()))
                .extracting(SensorListItemDTO::id).containsExactly(newer.getId(),older.getId());
        assertThat(sensorService.getSensorsForUser(otherOwner.getId()))
                .extracting(SensorListItemDTO::id).containsExactly(foreign.getId());
        assertThat(sensorService.getSensorsForUser(-1L)).isEmpty();
    }



    @Test
    void deletesAllDependentRowsWhilePreservingOtherSensorsAndOwners() {
        Sensor target = persistSensor(owner,"Target");
        Sensor sibling = persistSensor(owner,"Sibling");
        Sensor foreign = persistSensor(otherOwner,"Foreign");

        seedDependentRows(target.getId(),owner.getId());
        seedDependentRows(sibling.getId(),owner.getId());
        seedDependentRows(foreign.getId(),otherOwner.getId());

        assertDependentCounts(target.getId(),1);
        assertDependentCounts(sibling.getId(),1);
        assertDependentCounts(foreign.getId(),1);

        sensorService.deleteSensor(target.getId(),owner.getId());

        assertThat(sensorRepository.findById(target.getId())).isEmpty();
        assertDependentCounts(target.getId(),0);

        assertThat(sensorRepository.findById(sibling.getId())).isPresent();
        assertThat(sensorRepository.findById(foreign.getId())).isPresent();
        assertDependentCounts(sibling.getId(),1);
        assertDependentCounts(foreign.getId(),1);
        assertThat(appUserRepository.existsById(owner.getId())).isTrue();
        assertThat(appUserRepository.existsById(otherOwner.getId())).isTrue();

        assertThatThrownBy(() -> sensorService.deleteSensor(target.getId(),owner.getId()))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found");
    }



    private AppUser persistUser() {
        AppUser saved = appUserRepository.saveAndFlush(new AppUser("sensor-service-" + UUID.randomUUID() + "@example.com","test-password-hash",SENSOR_CREATED_AT));
        fixtureOwnerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser sensorOwner,String name) {
        return persistSensor(sensorOwner,name,SENSOR_CREATED_AT);
    }



    private Sensor persistSensor(AppUser sensorOwner,String name,Instant createdAt) {
        Sensor sensor = new Sensor(sensorOwner,SensorType.TEMPERATURE,name,"Istanbul","Kadikoy","Window","UTC",createdAt);
        sensor.assignIngestionTokenHash(tokenGenerator.generate().tokenHash(),createdAt);
        return sensorRepository.saveAndFlush(sensor);
    }



    private Sensor reload(Long sensorId) {
        return sensorRepository.findById(sensorId).orElseThrow();
    }



    private void invokeOwnedOperation(String operation,Long sensorId,Long ownerId) {
        switch (operation) {
            case "read" -> sensorService.getSensorForUser(sensorId,ownerId);
            case "form" -> sensorService.getSensorUpdateForm(sensorId,ownerId);
            case "update" -> sensorService.updateSensor(sensorId,ownerId,updateForm("Changed","Europe/Istanbul"));
            case "activate" -> sensorService.activateSensor(sensorId,ownerId);
            case "deactivate" -> sensorService.deactivateSensor(sensorId,ownerId);
            case "delete" -> sensorService.deleteSensor(sensorId,ownerId);
            default -> throw new IllegalArgumentException("Unknown test operation: " + operation);
        }
    }



    private void seedDependentRows(Long sensorId,Long ownerId) {
        Timestamp start = Timestamp.from(BUCKET_START);
        Timestamp hourEnd = Timestamp.from(BUCKET_START.plusSeconds(3600));
        Timestamp dayEnd = Timestamp.from(BUCKET_START.plusSeconds(86400));
        Timestamp recordedAt = Timestamp.from(BUCKET_START.plusSeconds(900));
        Timestamp now = Timestamp.from(TEST_INSTANT);

        jdbcTemplate.update("INSERT INTO sensor_readings (sensor_id,numeric_value,unit,recorded_at) VALUES (?,20.0,'C',?)",sensorId,recordedAt);
        jdbcTemplate.update("UPDATE sensors SET first_reading_at=?,updated_at=? WHERE id=?",recordedAt,now,sensorId);

        jdbcTemplate.update("""
                INSERT INTO alert_rules
                    (owner_id,sensor_id,rule_type,comparison_operator,threshold_value,threshold_unit,created_at,updated_at)
                VALUES (?,?,'NUMERIC_THRESHOLD','ABOVE',25.0,'C',?,?)
                """,ownerId,sensorId,start,start);

        jdbcTemplate.update("""
                INSERT INTO hourly_sensor_summaries
                    (sensor_id,bucket_start,bucket_end,source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,finalized_at,refreshed_at)
                VALUES (?,?,?,1,'C',20.0,20.0,20.0,?,?)
                """,sensorId,start,hourEnd,now,now);

        jdbcTemplate.update("""
                INSERT INTO daily_sensor_summaries
                    (sensor_id,local_date,time_zone_id,bucket_start,bucket_end,source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,finalized_at,refreshed_at)
                VALUES (?,DATE '2026-01-14','UTC',?,?,1,'C',20.0,20.0,20.0,?,?)
                """,sensorId,start,dayEnd,now,now);

        for (String stage : List.of("RAW_TO_HOURLY","HOURLY_TO_DAILY")) {
            jdbcTemplate.update("""
                    INSERT INTO sensor_rollup_checkpoints
                        (sensor_id,stage,coverage_started_at,covered_until,created_at,updated_at)
                    VALUES (?,?,?,?,?,?)
                    """,sensorId,stage,start,dayEnd,now,now);
        }
    }



    private void assertDependentCounts(Long sensorId,int copies) {
        for (Map.Entry<String,Long> entry : DEPENDENT_COUNTS.entrySet()) {
            Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM " + entry.getKey() + " WHERE sensor_id=?",Long.class,sensorId);
            assertThat(count).as("%s rows for sensor %s",entry.getKey(),sensorId).isEqualTo(entry.getValue() * copies);
        }
    }



    private static SensorCreateForm createForm(String name) {
        SensorCreateForm form = new SensorCreateForm();
        form.setName(name);
        form.setCity(" Istanbul ");
        form.setDistrict(" Kadikoy ");
        form.setInstallationLocation(" Window ");
        form.setTimezone(" UTC ");
        return form;
    }



    private static SensorUpdateForm updateForm(String name,String timezone) {
        SensorUpdateForm form = new SensorUpdateForm();
        form.setName(name);
        form.setCity(" Ankara ");
        form.setDistrict(" Cankaya ");
        form.setInstallationLocation(" Shelf ");
        form.setTimezone(timezone);
        return form;
    }
}