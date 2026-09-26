package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.UPDATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class SensorRepositoryIT {

    private static final String FIRST_TOKEN_HASH = "a".repeat(64);
    private static final String SECOND_TOKEN_HASH = "b".repeat(64);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void persistsAndReloadsSensorState(SensorType type) {
        AppUser owner = persistUser("owner@example.com");
        Instant firstReadingAt = CREATED_AT.plusSeconds(30);

        Sensor sensor = new Sensor(owner,type,"Living room","Istanbul","Kadikoy","Window","Europe/Istanbul",CREATED_AT);
        sensor.assignIngestionTokenHash(FIRST_TOKEN_HASH,UPDATED_AT);
        sensor.recordFirstReading(firstReadingAt,UPDATED_AT);
        sensor.deactivate(UPDATED_AT);

        Long sensorId = sensorRepository.saveAndFlush(sensor).getId();
        entityManager.clear();

        Sensor reloaded = sensorRepository.findByIdAndOwnerId(sensorId,owner.getId()).orElseThrow();

        assertThat(sensorId).isPositive();
        assertThat(reloaded.getId()).isEqualTo(sensorId);
        assertThat(reloaded.getOwner().getId()).isEqualTo(owner.getId());
        assertThat(reloaded.getType()).isEqualTo(type);
        assertThat(reloaded.getName()).isEqualTo("Living room");
        assertThat(reloaded.getCity()).isEqualTo("Istanbul");
        assertThat(reloaded.getDistrict()).isEqualTo("Kadikoy");
        assertThat(reloaded.getInstallationLocation()).isEqualTo("Window");
        assertThat(reloaded.getTimezone()).isEqualTo("Europe/Istanbul");
        assertThat(reloaded.isActive()).isFalse();
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(FIRST_TOKEN_HASH);
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(firstReadingAt);
        assertThat(reloaded.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }



    @Test
    void restrictsBothOwnerScopedLookupsToTheRequestedOwner() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");
        Sensor owned = persistSensor(owner,"Living room",CREATED_AT);
        Sensor foreign = persistSensor(otherOwner,"Living room",CREATED_AT);
        flushAndClear();

        assertThat(sensorRepository.findByIdAndOwnerId(owned.getId(),owner.getId()).map(Sensor::getId)).contains(owned.getId());
        assertThat(sensorRepository.findByIdAndOwnerId(foreign.getId(),otherOwner.getId()).map(Sensor::getId)).contains(foreign.getId());
        assertThat(sensorRepository.findByIdAndOwnerId(foreign.getId(),owner.getId())).isEmpty();
        assertThat(sensorRepository.findByIdAndOwnerId(-1L,owner.getId())).isEmpty();
        assertThat(sensorRepository.findByIdAndOwnerId(owned.getId(),-1L)).isEmpty();

        assertThat(sensorRepository.findByIdAndOwnerIdForUpdate(owned.getId(),owner.getId()).map(Sensor::getId)).contains(owned.getId());
        assertThat(sensorRepository.findByIdAndOwnerIdForUpdate(foreign.getId(),otherOwner.getId()).map(Sensor::getId)).contains(foreign.getId());
        assertThat(sensorRepository.findByIdAndOwnerIdForUpdate(foreign.getId(),owner.getId())).isEmpty();
        assertThat(sensorRepository.findByIdAndOwnerIdForUpdate(-1L,owner.getId())).isEmpty();
        assertThat(sensorRepository.findByIdAndOwnerIdForUpdate(owned.getId(),-1L)).isEmpty();
    }



    @Test
    void findsSensorsForUpdateById() {
        AppUser owner = persistUser("owner@example.com");
        Sensor first = persistSensor(owner,"Living room",CREATED_AT);
        Sensor second = persistSensor(owner,"Kitchen",CREATED_AT);
        flushAndClear();

        assertThat(sensorRepository.findByIdForUpdate(first.getId()).map(Sensor::getId)).contains(first.getId());
        assertThat(sensorRepository.findByIdForUpdate(second.getId()).map(Sensor::getId)).contains(second.getId());
        assertThat(sensorRepository.findByIdForUpdate(-1L)).isEmpty();
    }



    @Test
    void listsOnlyTheOwnersSensorsNewestFirstIncludingInactiveSensors() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");

        Sensor middle = persistSensor(owner,"Middle",CREATED_AT.plusSeconds(60));
        Sensor oldest = persistSensor(owner,"Oldest",CREATED_AT);
        persistSensor(otherOwner,"Foreign",CREATED_AT.plusSeconds(180));
        Sensor newest = persistSensor(owner,"Newest",CREATED_AT.plusSeconds(120));
        oldest.deactivate(CREATED_AT.plusSeconds(300));
        flushAndClear();

        assertThat(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId()))
                .extracting(Sensor::getId)
                .containsExactly(newest.getId(),middle.getId(),oldest.getId());
        assertThat(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(-1L)).isEmpty();
    }



    @Test
    void checksNamesIgnoringCaseWithinTheOwnerAndExcludesTheEditedSensor() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");
        Sensor livingRoom = persistSensor(owner,"Living room",CREATED_AT);
        Sensor kitchen = persistSensor(owner,"Kitchen",CREATED_AT);
        persistSensor(otherOwner,"Garage",CREATED_AT);
        flushAndClear();

        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCase(owner.getId(),"LIVING ROOM")).isTrue();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCase(owner.getId(),"missing")).isFalse();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCase(owner.getId(),"GARAGE")).isFalse();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCase(otherOwner.getId(),"LIVING ROOM")).isFalse();

        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner.getId(),"LIVING ROOM",livingRoom.getId())).isFalse();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner.getId(),"LIVING ROOM",kitchen.getId())).isTrue();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner.getId(),"missing",livingRoom.getId())).isFalse();
        assertThat(sensorRepository.existsByOwnerIdAndNameIgnoreCaseAndIdNot(owner.getId(),"GARAGE",livingRoom.getId())).isFalse();
    }



    @Test
    void rejectsCaseInsensitiveDuplicateNamesForTheSameOwnerThroughDirectSql() {
        AppUser owner = persistUser("owner@example.com");
        persistSensor(owner,"Living room",CREATED_AT);
        flushAndClear();

        assertThatThrownBy(() -> insertSensor(owner.getId(),"LIVING ROOM",null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_sensors_owner_name_lower");
    }



    @Test
    void allowsCaseEquivalentNamesForDifferentOwners() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");

        insertSensor(owner.getId(),"Living room",null);
        insertSensor(otherOwner.getId(),"LIVING ROOM",null);
        flushAndClear();

        assertThat(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId()))
                .extracting(Sensor::getName)
                .containsExactly("Living room");
        assertThat(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(otherOwner.getId()))
                .extracting(Sensor::getName)
                .containsExactly("LIVING ROOM");
    }



    @Test
    void findsSensorsByTokenHashAcrossOwnersIncludingInactiveSensors() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");

        Sensor active = persistSensor(owner,"Active",CREATED_AT);
        active.assignIngestionTokenHash(FIRST_TOKEN_HASH,UPDATED_AT);

        Sensor inactive = persistSensor(otherOwner,"Inactive",CREATED_AT);
        inactive.assignIngestionTokenHash(SECOND_TOKEN_HASH,UPDATED_AT);
        inactive.deactivate(UPDATED_AT);

        persistSensor(owner,"Without token",CREATED_AT);
        flushAndClear();

        assertThat(sensorRepository.findByIngestionTokenHashForUpdate(FIRST_TOKEN_HASH).map(Sensor::getId)).contains(active.getId());
        assertThat(sensorRepository.findByIngestionTokenHashForUpdate(SECOND_TOKEN_HASH).map(Sensor::getId)).contains(inactive.getId());
        assertThat(sensorRepository.findByIngestionTokenHashForUpdate("c".repeat(64))).isEmpty();
    }



    @Test
    void rejectsDuplicateNonNullTokenHashesAcrossOwnersThroughDirectSql() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");
        Sensor sensor = persistSensor(owner,"Living room",CREATED_AT);
        sensor.assignIngestionTokenHash(FIRST_TOKEN_HASH,UPDATED_AT);
        flushAndClear();

        assertThatThrownBy(() -> insertSensor(otherOwner.getId(),"Kitchen",FIRST_TOKEN_HASH))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_sensors_ingestion_token_hash");
    }



    @Test
    void allowsMultipleSensorsWithoutTokenHashes() {
        AppUser owner = persistUser("owner@example.com");

        insertSensor(owner.getId(),"Living room",null);
        insertSensor(owner.getId(),"Kitchen",null);
        flushAndClear();

        assertThat(sensorRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId()))
                .hasSize(2)
                .allSatisfy(sensor -> assertThat(sensor.getIngestionTokenHash()).isNull());
    }



    @Test
    void findsHistorySensorsInIdOrderWithOptionalStageSpecificRollupCoverage() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");

        Instant firstWithoutCheckpoints = CREATED_AT.plusSeconds(180);
        Instant firstHourlyOnly = CREATED_AT.plusSeconds(120);
        Instant firstDailyOnly = CREATED_AT.plusSeconds(60);
        Instant firstBoth = CREATED_AT.plusSeconds(240);

        Sensor noHistory = persistSensor(owner,"No history",CREATED_AT);
        Sensor withoutCheckpoints = persistSensorWithHistory(owner,"No checkpoints","Europe/Istanbul",firstWithoutCheckpoints);
        Sensor hourlyOnly = persistSensorWithHistory(owner,"Hourly only","UTC",firstHourlyOnly);
        Sensor dailyOnly = persistSensorWithHistory(owner,"Daily only","Europe/Berlin",firstDailyOnly);
        Sensor both = persistSensorWithHistory(otherOwner,"Both checkpoints","Europe/Istanbul",firstBoth);
        both.deactivate(CREATED_AT.plusSeconds(600));

        Instant hourlyOnlyUntil = CREATED_AT.plusSeconds(3600);
        Instant dailyOnlyUntil = Instant.parse("2026-01-15T23:00:00Z");
        Instant bothHourlyUntil = Instant.parse("2026-01-16T12:00:00Z");
        Instant bothDailyUntil = Instant.parse("2026-01-15T21:00:00Z");

        persistCheckpoint(noHistory,RollupStage.RAW_TO_HOURLY,hourlyOnlyUntil);
        persistCheckpoint(hourlyOnly,RollupStage.RAW_TO_HOURLY,hourlyOnlyUntil);
        persistCheckpoint(dailyOnly,RollupStage.HOURLY_TO_DAILY,dailyOnlyUntil);
        persistCheckpoint(both,RollupStage.RAW_TO_HOURLY,bothHourlyUntil);
        persistCheckpoint(both,RollupStage.HOURLY_TO_DAILY,bothDailyUntil);
        flushAndClear();

        assertThat(sensorRepository.findSensorIdsWithReadingHistory())
                .containsExactly(withoutCheckpoints.getId(),hourlyOnly.getId(),dailyOnly.getId(),both.getId());

        assertThat(sensorRepository.findSensorsForRollup())
                .extracting(
                        RollupCandidateProjection::getId,RollupCandidateProjection::getTimezone,
                        RollupCandidateProjection::getFirstReadingAt,
                        RollupCandidateProjection::getHourlyCoveredUntil,RollupCandidateProjection::getDailyCoveredUntil)
                .containsExactly(
                        tuple(withoutCheckpoints.getId(),"Europe/Istanbul",firstWithoutCheckpoints,null,null),
                        tuple(hourlyOnly.getId(),"UTC",firstHourlyOnly,hourlyOnlyUntil,null),
                        tuple(dailyOnly.getId(),"Europe/Berlin",firstDailyOnly,null,dailyOnlyUntil),
                        tuple(both.getId(),"Europe/Istanbul",firstBoth,bothHourlyUntil,bothDailyUntil));
    }



    @Test
    void returnsNoHistoryCandidatesWhenFirstReadingIsAbsent() {
        AppUser owner = persistUser("owner@example.com");
        persistSensor(owner,"Active without history",CREATED_AT);
        Sensor inactive = persistSensor(owner,"Inactive without history",CREATED_AT);
        inactive.deactivate(UPDATED_AT);
        flushAndClear();

        assertThat(sensorRepository.findSensorIdsWithReadingHistory()).isEmpty();
        assertThat(sensorRepository.findSensorsForRollup()).isEmpty();
    }



    private AppUser persistUser(String email) {
        return appUserRepository.saveAndFlush(new AppUser(email,"test-password-hash",CREATED_AT));
    }



    private Sensor persistSensor(AppUser owner,String name,Instant createdAt) {
        return sensorRepository.save(new Sensor(owner,SensorType.TEMPERATURE,name,"Istanbul","Kadikoy","Window","UTC",createdAt));
    }



    private Sensor persistSensorWithHistory(AppUser owner,String name,String timezone,Instant firstReadingAt) {
        Sensor sensor = new Sensor(owner,SensorType.TEMPERATURE,name,"Istanbul","Kadikoy","Window",timezone,CREATED_AT);
        sensor.recordFirstReading(firstReadingAt,firstReadingAt);
        return sensorRepository.save(sensor);
    }



    private void persistCheckpoint(Sensor sensor,RollupStage stage,Instant coveredUntil) {
        entityManager.persist(SensorRollupCheckpoint.initialize(sensor,stage,coveredUntil,coveredUntil.plusSeconds(60)));
    }



    private void insertSensor(Long ownerId,String name,String tokenHash) {
        Timestamp createdAt = Timestamp.from(CREATED_AT);

        jdbcTemplate.update("""
                INSERT INTO sensors (
                    owner_id,type,name,city,district,installation_location,
                    time_zone_id,created_at,updated_at,ingestion_token_hash
                )
                VALUES (?,'TEMPERATURE',?,'Istanbul','Kadikoy','Window','UTC',?,?,?)
                """,ownerId,name,createdAt,createdAt,tokenHash);
    }



    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}