package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class HourlySensorSummaryRepositoryIT {

    private static final Instant START = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private HourlySensorSummaryRepository summaryRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @ParameterizedTest
    @MethodSource("validAggregates")
    void persistsAndReloadsNumericBooleanAndEmptyAggregates(SensorType type,SensorSummaryAggregate aggregate) {
        Sensor sensor = persistSensor("target",type);
        HourlySensorSummary summary = summaryRepository.saveAndFlush(
                HourlySensorSummary.create(sensor,START,aggregate,END));
        flushAndClear();

        HourlySensorSummary reloaded = summaryRepository.findBySensorIdAndBucketStart(sensor.getId(),START).orElseThrow();

        assertThat(reloaded.getId()).isPositive().isEqualTo(summary.getId());
        assertThat(reloaded.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(reloaded.getBucketStart()).isEqualTo(START);
        assertThat(reloaded.getBucketEnd()).isEqualTo(END);
        assertThat(reloaded.getFinalizedAt()).isEqualTo(END);
        assertThat(reloaded.getRefreshedAt()).isEqualTo(END);
        assertAggregate(reloaded,aggregate);
    }



    @Test
    void persistsRefreshesWithoutChangingBucketIdentityOrFinalizationTime() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        HourlySensorSummary original = persistSummary(sensor,START);
        flushAndClear();

        SensorSummaryAggregate replacement = SensorSummaryAggregate.numeric(
                3,MeasurementUnit.C,new BigDecimal("60.1234567890123456789"),10.0,30.0);
        Instant refreshedAt = END.plusSeconds(60);
        HourlySensorSummary pending = summaryRepository.findById(original.getId()).orElseThrow();
        pending.refresh(replacement,refreshedAt);
        flushAndClear();

        HourlySensorSummary reloaded = summaryRepository.findById(original.getId()).orElseThrow();

        assertThat(reloaded.getBucketStart()).isEqualTo(START);
        assertThat(reloaded.getBucketEnd()).isEqualTo(END);
        assertThat(reloaded.getFinalizedAt()).isEqualTo(END);
        assertThat(reloaded.getRefreshedAt()).isEqualTo(refreshedAt);
        assertAggregate(reloaded,replacement);

        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        reloaded.refresh(empty,refreshedAt.plusSeconds(60));
        flushAndClear();

        HourlySensorSummary cleared = summaryRepository.findById(original.getId()).orElseThrow();

        assertAggregate(cleared,empty);
        assertThat(cleared.getFinalizedAt()).isEqualTo(END);
        assertThat(cleared.getRefreshedAt()).isEqualTo(refreshedAt.plusSeconds(60));
    }



    @Test
    void scopesBucketLookupAndReturnsOrderedHalfOpenDailyRollupRanges() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE);
        Sensor other = persistSensor("other",SensorType.TEMPERATURE);

        HourlySensorSummary second = persistSummary(target,END);
        persistSummary(target,START.minusSeconds(3600));
        HourlySensorSummary first = persistSummary(target,START);
        persistSummary(target,END.plusSeconds(3600));
        HourlySensorSummary foreign = persistSummary(other,START);
        flushAndClear();

        assertThat(summaryRepository.findBySensorIdAndBucketStart(target.getId(),START).map(HourlySensorSummary::getId))
                .contains(first.getId());
        assertThat(summaryRepository.findBySensorIdAndBucketStart(other.getId(),START).map(HourlySensorSummary::getId))
                .contains(foreign.getId());
        assertThat(summaryRepository.findBySensorIdAndBucketStart(-1L,START)).isEmpty();
        assertThat(summaryRepository.findBySensorIdAndBucketStart(target.getId(),START.plusSeconds(1))).isEmpty();

        assertThat(summaryRepository.findForDailyRollup(target.getId(),START,END.plusSeconds(3600)))
                .extracting(HourlySensorSummary::getId)
                .containsExactly(first.getId(),second.getId());

        assertThat(summaryRepository.findForDailyRollup(target.getId(),START.plusSeconds(1),END.plusSeconds(3600)))
                .extracting(HourlySensorSummary::getId)
                .containsExactly(second.getId());

        assertThat(summaryRepository.findForDailyRollup(-1L,START,END)).isEmpty();
    }



    @Test
    void findsOverlappingStatisticsBucketsWithStrictRangeAndRetentionBoundaries() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE);
        Sensor other = persistSensor("other",SensorType.TEMPERATURE);

        HourlySensorSummary second = persistSummary(target,END);
        persistSummary(target,START.minusSeconds(3600));
        persistSummary(target,END.plusSeconds(3600));
        HourlySensorSummary first = persistSummary(target,START);
        persistSummary(other,START);
        flushAndClear();

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),START.minusSeconds(7200),START,END.plusSeconds(3600)))
                .extracting(HourlySensorSummary::getId)
                .containsExactly(first.getId(),second.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),START.plusSeconds(1800),START.plusSeconds(1800),END.plusSeconds(1800)))
                .extracting(HourlySensorSummary::getId)
                .containsExactly(first.getId(),second.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),END,START,END.plusSeconds(3600)))
                .extracting(HourlySensorSummary::getId)
                .containsExactly(second.getId());

        assertThat(summaryRepository.findForStatisticsRange(-1L,START,START,END)).isEmpty();
    }



    @Test
    void checksExpiredBucketsUsingAnInclusiveEndBoundaryAndSensorScope() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE);
        Sensor withoutSummaries = persistSensor("empty",SensorType.TEMPERATURE);
        persistSummary(target,START);
        flushAndClear();

        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(target.getId(),END.minusNanos(1000))).isFalse();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(target.getId(),END)).isTrue();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(withoutSummaries.getId(),END)).isFalse();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(-1L,END)).isFalse();
    }



    @Test
    void requiresDailyCheckpointCoverageForTheSameSensorBeforeRetentionIsEligible() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE);
        Sensor other = persistSensor("other",SensorType.TEMPERATURE);
        persistSummary(target,START);
        flushAndClear();

        assertThat(summaryRepository.existsEligibleForRetentionPurge(target.getId(),END)).isFalse();

        persistCheckpoint(target,RollupStage.RAW_TO_HOURLY,END);
        persistCheckpoint(other,RollupStage.HOURLY_TO_DAILY,END);
        flushAndClear();

        assertThat(summaryRepository.existsEligibleForRetentionPurge(target.getId(),END)).isFalse();
        assertThat(summaryRepository.existsEligibleForRetentionPurge(other.getId(),END)).isFalse();

        persistCheckpoint(target,RollupStage.HOURLY_TO_DAILY,END);
        flushAndClear();

        assertThat(summaryRepository.existsEligibleForRetentionPurge(target.getId(),END)).isTrue();
        assertThat(summaryRepository.existsEligibleForRetentionPurge(-1L,END)).isFalse();
    }



    @ParameterizedTest
    @CsvSource({
            "-1,0,false",
            "0,-1,false",
            "0,0,true",
            "900,0,true",
            "-1,3600,false",
            "3600,0,true"
    })
    void requiresBothRetentionCutoffAndCompletedUtcHourCoverage(
            long coverageOffsetSeconds,long retentionOffsetSeconds,boolean expected) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        persistSummary(sensor,START);
        persistCheckpoint(sensor,RollupStage.HOURLY_TO_DAILY,END.plusSeconds(coverageOffsetSeconds));
        flushAndClear();

        assertThat(summaryRepository.existsEligibleForRetentionPurge(
                sensor.getId(),END.plusSeconds(retentionOffsetSeconds))).isEqualTo(expected);
    }



    @Test
    void rejectsDuplicateSensorBucketsThroughDirectSql() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        HourlySensorSummary summary = persistSummary(sensor,START);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO hourly_sensor_summaries (
                    sensor_id,bucket_start,bucket_end,source_sample_count,unit,
                    numeric_sum,numeric_minimum,numeric_maximum,true_sample_count,finalized_at,refreshed_at
                )
                SELECT sensor_id,bucket_start,bucket_end,source_sample_count,unit,
                       numeric_sum,numeric_minimum,numeric_maximum,true_sample_count,finalized_at,refreshed_at
                FROM hourly_sensor_summaries
                WHERE id = ?
                """,summary.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_hourly_sensor_summaries_sensor_bucket_start");
    }



    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "source_sample_count = -1",
            "source_sample_count = 0",
            "unit = 'F'",
            "unit = NULL",
            "numeric_sum = NULL",
            "numeric_minimum = NULL",
            "numeric_maximum = NULL",
            "true_sample_count = 1",
            "unit = NULL,numeric_sum = NULL,numeric_minimum = NULL,numeric_maximum = NULL,true_sample_count = -1",
            "unit = NULL,numeric_sum = NULL,numeric_minimum = NULL,numeric_maximum = NULL,true_sample_count = 3",
            "numeric_sum = 'NaN'::NUMERIC",
            "numeric_sum = 'Infinity'::NUMERIC",
            "numeric_sum = '-Infinity'::NUMERIC",
            "numeric_minimum = 'NaN'::DOUBLE PRECISION",
            "numeric_minimum = 'Infinity'::DOUBLE PRECISION",
            "numeric_minimum = '-Infinity'::DOUBLE PRECISION",
            "numeric_maximum = 'NaN'::DOUBLE PRECISION",
            "numeric_maximum = 'Infinity'::DOUBLE PRECISION",
            "numeric_maximum = '-Infinity'::DOUBLE PRECISION",
            "numeric_minimum = 21",
            "numeric_minimum = -273.16",
            "numeric_sum = -546.31",
            "unit = 'PERCENT',numeric_minimum = -0.01",
            "unit = 'PERCENT',numeric_maximum = 100.01",
            "unit = 'PERCENT',numeric_sum = -0.01",
            "unit = 'PERCENT',numeric_sum = 200.01",
            "bucket_start = bucket_start + INTERVAL '1 second',bucket_end = bucket_end + INTERVAL '1 second'",
            "bucket_end = bucket_start + INTERVAL '2 hours'",
            "refreshed_at = finalized_at - INTERVAL '1 microsecond'"
    })
    void rejectsInvalidSummaryValuesThroughDirectSql(String assignment) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        HourlySensorSummary summary = persistSummary(sensor,START);
        flushAndClear();

        assertSqlState("23514",() -> jdbcTemplate.update(
                "UPDATE hourly_sensor_summaries SET " + assignment + " WHERE id = ?",summary.getId()));
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "sensor_id","bucket_start","bucket_end","source_sample_count","finalized_at","refreshed_at"
    })
    void rejectsNullRequiredColumnsThroughDirectSql(String column) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        HourlySensorSummary summary = persistSummary(sensor,START);
        flushAndClear();

        assertSqlState("23502",() -> jdbcTemplate.update(
                "UPDATE hourly_sensor_summaries SET " + column + " = NULL WHERE id = ?",summary.getId()));
    }



    @Test
    void rejectsAMissingSensorReferenceThroughDirectSql() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE);
        HourlySensorSummary summary = persistSummary(sensor,START);
        flushAndClear();

        assertSqlState("23503",() -> jdbcTemplate.update(
                "UPDATE hourly_sensor_summaries SET sensor_id = ? WHERE id = ?",-1L,summary.getId()));
    }



    private static Stream<Arguments> validAggregates() {
        return Stream.of(
                Arguments.of(SensorType.TEMPERATURE,SensorSummaryAggregate.numeric(
                        2,MeasurementUnit.C,new BigDecimal("-546.30"),-273.15,-273.15)),
                Arguments.of(SensorType.HUMIDITY,SensorSummaryAggregate.numeric(
                        2,MeasurementUnit.PERCENT,new BigDecimal("100"),0.0,100.0)),
                Arguments.of(SensorType.MOTION,SensorSummaryAggregate.booleanSamples(2,1)),
                Arguments.of(SensorType.TEMPERATURE,SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C)),
                Arguments.of(SensorType.HUMIDITY,SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT)),
                Arguments.of(SensorType.MOTION,SensorSummaryAggregate.emptyBoolean()));
    }



    private Sensor persistSensor(String name,SensorType type) {
        AppUser owner = appUserRepository.saveAndFlush(new AppUser(name + "@example.com","test-password-hash",CREATED_AT));
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT));
    }



    private HourlySensorSummary persistSummary(Sensor sensor,Instant bucketStart) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("30"),10.0,20.0);
        return summaryRepository.saveAndFlush(HourlySensorSummary.create(sensor,bucketStart,aggregate,bucketStart.plusSeconds(3600)));
    }



    private void persistCheckpoint(Sensor sensor,RollupStage stage,Instant coveredUntil) {
        long bucketSeconds = stage == RollupStage.RAW_TO_HOURLY ? 3600 : 86400;
        Instant coverageStart = coveredUntil.minusSeconds(bucketSeconds);
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,coverageStart,coveredUntil);
        checkpoint.recordAttempt(coverageStart,coveredUntil);
        checkpoint.advanceContiguously(coverageStart,coveredUntil,coveredUntil);
        checkpointRepository.saveAndFlush(checkpoint);
    }



    private void assertAggregate(HourlySensorSummary summary,SensorSummaryAggregate expected) {
        assertThat(summary.toAggregate()).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo,BigDecimal.class)
                .isEqualTo(expected);
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