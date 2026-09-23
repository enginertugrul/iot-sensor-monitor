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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class SensorRollupCheckpointRepositoryIT {

    private static final Instant START = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant INITIALIZED_AT = START.plusSeconds(172800);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @Test
    void scopesLookupsBySensorAndStageAndAllowsBothStagesForOneSensor() {
        Sensor target = persistSensor("target");
        Sensor other = persistSensor("other");
        Sensor withoutCheckpoints = persistSensor("empty");

        SensorRollupCheckpoint hourly = persistCheckpoint(target,RollupStage.RAW_TO_HOURLY,START);
        SensorRollupCheckpoint daily = persistCheckpoint(target,RollupStage.HOURLY_TO_DAILY,START.plusSeconds(900));
        SensorRollupCheckpoint foreign = persistCheckpoint(other,RollupStage.RAW_TO_HOURLY,START);
        flushAndClear();

        assertThat(checkpointRepository.findBySensorIdAndStage(target.getId(),RollupStage.RAW_TO_HOURLY)
                .map(SensorRollupCheckpoint::getId)).contains(hourly.getId());
        assertThat(checkpointRepository.findBySensorIdAndStage(target.getId(),RollupStage.HOURLY_TO_DAILY)
                .map(SensorRollupCheckpoint::getId)).contains(daily.getId());
        assertThat(checkpointRepository.findBySensorIdAndStage(other.getId(),RollupStage.RAW_TO_HOURLY)
                .map(SensorRollupCheckpoint::getId)).contains(foreign.getId());

        assertThat(checkpointRepository.findBySensorIdAndStage(other.getId(),RollupStage.HOURLY_TO_DAILY)).isEmpty();
        assertThat(checkpointRepository.findBySensorIdAndStage(withoutCheckpoints.getId(),RollupStage.RAW_TO_HOURLY)).isEmpty();
        assertThat(checkpointRepository.findBySensorIdAndStage(-1L,RollupStage.RAW_TO_HOURLY)).isEmpty();

        SensorRollupCheckpoint reloadedDaily = checkpointRepository.findById(daily.getId()).orElseThrow();

        assertThat(reloadedDaily.getCoverageStartedAt()).isEqualTo(START.plusSeconds(900));
        assertThat(reloadedDaily.getCoveredUntil()).isEqualTo(START.plusSeconds(900));
        assertHistoryCleared(reloadedDaily);
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void persistsInitializationAttemptsAdvancementAndInvalidation(RollupStage stage) {
        Sensor sensor = persistSensor("target");
        long bucketSeconds = stage == RollupStage.RAW_TO_HOURLY ? 3600 : 86400;
        Instant bucketEnd = START.plusSeconds(bucketSeconds);
        Instant attemptedAt = bucketEnd.plusSeconds(30);
        Instant successfulAt = bucketEnd.plusSeconds(60);

        SensorRollupCheckpoint original = checkpointRepository.saveAndFlush(
                SensorRollupCheckpoint.initialize(sensor,stage,START,START));
        flushAndClear();

        SensorRollupCheckpoint initialized = checkpointRepository.findBySensorIdAndStage(sensor.getId(),stage).orElseThrow();

        assertThat(initialized.getId()).isPositive().isEqualTo(original.getId());
        assertThat(initialized.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(initialized.getStage()).isEqualTo(stage);
        assertThat(initialized.getCoverageStartedAt()).isEqualTo(START);
        assertThat(initialized.getCoveredUntil()).isEqualTo(START);
        assertThat(initialized.getCreatedAt()).isEqualTo(START);
        assertThat(initialized.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(initialized);

        initialized.recordAttempt(START,attemptedAt);
        flushAndClear();

        SensorRollupCheckpoint attempted = checkpointRepository.findById(original.getId()).orElseThrow();

        assertThat(attempted.getCoveredUntil()).isEqualTo(START);
        assertThat(attempted.getLastAttemptedBucketStart()).isEqualTo(START);
        assertThat(attempted.getLastAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(attempted.getUpdatedAt()).isEqualTo(attemptedAt);
        assertThat(attempted.getLastSuccessfulBucketStart()).isNull();
        assertThat(attempted.getLastSuccessfulBucketEnd()).isNull();
        assertThat(attempted.getLastSuccessfulAt()).isNull();
        assertThat(attempted.getLastAdvancedAt()).isNull();

        attempted.advanceContiguously(START,bucketEnd,successfulAt);
        flushAndClear();

        SensorRollupCheckpoint advanced = checkpointRepository.findById(original.getId()).orElseThrow();

        assertThat(advanced.getCoverageStartedAt()).isEqualTo(START);
        assertThat(advanced.getCoveredUntil()).isEqualTo(bucketEnd);
        assertThat(advanced.getLastAttemptedBucketStart()).isEqualTo(START);
        assertThat(advanced.getLastAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(advanced.getLastSuccessfulBucketStart()).isEqualTo(START);
        assertThat(advanced.getLastSuccessfulBucketEnd()).isEqualTo(bucketEnd);
        assertThat(advanced.getLastSuccessfulAt()).isEqualTo(successfulAt);
        assertThat(advanced.getLastAdvancedAt()).isEqualTo(successfulAt);
        assertThat(advanced.getCreatedAt()).isEqualTo(START);
        assertThat(advanced.getUpdatedAt()).isEqualTo(successfulAt);

        Instant invalidatedFrom = START.minusSeconds(bucketSeconds);
        Instant invalidatedAt = successfulAt.plusSeconds(60);
        advanced.invalidateFrom(invalidatedFrom,invalidatedAt);
        flushAndClear();

        SensorRollupCheckpoint invalidated = checkpointRepository.findById(original.getId()).orElseThrow();

        assertThat(invalidated.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(invalidated.getStage()).isEqualTo(stage);
        assertThat(invalidated.getCoverageStartedAt()).isEqualTo(invalidatedFrom);
        assertThat(invalidated.getCoveredUntil()).isEqualTo(invalidatedFrom);
        assertThat(invalidated.getCreatedAt()).isEqualTo(START);
        assertThat(invalidated.getUpdatedAt()).isEqualTo(invalidatedAt);
        assertHistoryCleared(invalidated);
    }



    @Test
    void projectsOldestCoveragePerStageAndReflectsPersistedAdvancement() {
        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY)).isEmpty();
        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY)).isEmpty();

        Sensor first = persistSensor("first");
        Sensor second = persistSensor("second");

        persistCheckpoint(first,RollupStage.RAW_TO_HOURLY,START.plusSeconds(7200));
        SensorRollupCheckpoint oldestHourly = persistCheckpoint(second,RollupStage.RAW_TO_HOURLY,END);
        persistCheckpoint(first,RollupStage.HOURLY_TO_DAILY,START.minusSeconds(900));
        persistCheckpoint(second,RollupStage.HOURLY_TO_DAILY,START.plusSeconds(86400));
        flushAndClear();

        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY)).contains(END);
        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY))
                .contains(START.minusSeconds(900));

        SensorRollupCheckpoint pending = checkpointRepository.findById(oldestHourly.getId()).orElseThrow();
        pending.recordAttempt(END,INITIALIZED_AT);
        pending.advanceContiguously(END,END.plusSeconds(3600),INITIALIZED_AT);
        flushAndClear();

        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY))
                .contains(START.plusSeconds(7200));
        assertThat(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY))
                .contains(START.minusSeconds(900));
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void rejectsDuplicateSensorStagesThroughDirectSql(RollupStage stage) {
        Sensor sensor = persistSensor("target");
        SensorRollupCheckpoint checkpoint = persistCheckpoint(sensor,stage,START);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO sensor_rollup_checkpoints (
                    sensor_id,stage,coverage_started_at,covered_until,created_at,updated_at
                )
                SELECT sensor_id,stage,coverage_started_at,covered_until,created_at,updated_at
                FROM sensor_rollup_checkpoints
                WHERE id = ?
                """,checkpoint.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_sensor_rollup_checkpoints_sensor_stage");
    }



    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "stage = 'UNKNOWN'",
            "coverage_started_at = covered_until + INTERVAL '1 hour'",
            "coverage_started_at = coverage_started_at - INTERVAL '1 microsecond'",
            "covered_until = covered_until + INTERVAL '1 microsecond'",
            "last_attempted_bucket_start = NULL",
            "last_attempted_at = NULL",
            "last_successful_bucket_start = NULL",
            "last_successful_bucket_end = NULL",
            "last_successful_at = NULL",
            "last_successful_bucket_end = last_successful_bucket_start",
            "last_successful_bucket_start = coverage_started_at - INTERVAL '1 hour'",
            "last_successful_bucket_end = covered_until + INTERVAL '1 hour'",
            "last_attempted_at = created_at - INTERVAL '1 microsecond'",
            "last_attempted_at = updated_at + INTERVAL '1 microsecond'",
            "last_successful_at = created_at - INTERVAL '1 microsecond'",
            "last_successful_at = updated_at + INTERVAL '1 microsecond'",
            "last_advanced_at = created_at - INTERVAL '1 microsecond'",
            "last_advanced_at = updated_at + INTERVAL '1 microsecond'",
            "updated_at = created_at - INTERVAL '1 microsecond'"
    })
    void rejectsInvalidCheckpointValuesThroughDirectSql(String assignment) {
        Sensor sensor = persistSensor("target");
        SensorRollupCheckpoint checkpoint = persistSuccessfulCheckpoint(sensor);
        flushAndClear();

        assertSqlState("23514",() -> jdbcTemplate.update(
                "UPDATE sensor_rollup_checkpoints SET " + assignment + " WHERE id = ?",checkpoint.getId()));
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "sensor_id","stage","coverage_started_at","covered_until","created_at","updated_at"
    })
    void rejectsNullRequiredColumnsThroughDirectSql(String column) {
        Sensor sensor = persistSensor("target");
        SensorRollupCheckpoint checkpoint = persistSuccessfulCheckpoint(sensor);
        flushAndClear();

        assertSqlState("23502",() -> jdbcTemplate.update(
                "UPDATE sensor_rollup_checkpoints SET " + column + " = NULL WHERE id = ?",checkpoint.getId()));
    }



    @Test
    void rejectsAMissingSensorReferenceThroughDirectSql() {
        Sensor sensor = persistSensor("target");
        SensorRollupCheckpoint checkpoint = persistCheckpoint(sensor,RollupStage.RAW_TO_HOURLY,START);
        flushAndClear();

        assertSqlState("23503",() -> jdbcTemplate.update(
                "UPDATE sensor_rollup_checkpoints SET sensor_id = ? WHERE id = ?",-1L,checkpoint.getId()));
    }



    private Sensor persistSensor(String name) {
        AppUser owner = appUserRepository.saveAndFlush(new AppUser(name + "@example.com","test-password-hash",CREATED_AT));
        return sensorRepository.saveAndFlush(
                new Sensor(owner,SensorType.TEMPERATURE,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT));
    }



    private SensorRollupCheckpoint persistCheckpoint(Sensor sensor,RollupStage stage,Instant coverageStart) {
        return checkpointRepository.saveAndFlush(
                SensorRollupCheckpoint.initialize(sensor,stage,coverageStart,INITIALIZED_AT));
    }



    private SensorRollupCheckpoint persistSuccessfulCheckpoint(Sensor sensor) {
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,RollupStage.RAW_TO_HOURLY,START,START);
        checkpoint.recordAttempt(START,END);
        checkpoint.advanceContiguously(START,END,END.plusSeconds(60));
        return checkpointRepository.saveAndFlush(checkpoint);
    }



    private void assertHistoryCleared(SensorRollupCheckpoint checkpoint) {
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
    }



    private void assertSqlState(String expectedState,Runnable action) {
        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,action::run);

        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) exception.getMostSpecificCause();
        assertThat(sqlException.getSQLState()).isEqualTo(expectedState);
    }



    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}