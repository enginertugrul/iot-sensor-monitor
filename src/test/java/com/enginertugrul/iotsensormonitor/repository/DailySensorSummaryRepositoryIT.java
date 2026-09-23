package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class DailySensorSummaryRepositoryIT {

    private static final LocalDate DATE = LocalDate.of(2026,1,16);
    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final Instant START = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-17T00:00:00Z");

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private DailySensorSummaryRepository summaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @ParameterizedTest
    @MethodSource("validAggregates")
    void persistsAndReloadsNumericBooleanAndEmptyAggregates(SensorType type,SensorSummaryAggregate aggregate) {
        Sensor sensor = persistSensor("target",type,"UTC");
        DailySensorSummary summary = summaryRepository.saveAndFlush(
                DailySensorSummary.create(sensor,DATE,UTC,aggregate,END));
        flushAndClear();

        DailySensorSummary reloaded = summaryRepository.findBySensorIdAndBucketStart(sensor.getId(),START).orElseThrow();

        assertThat(reloaded.getId()).isPositive().isEqualTo(summary.getId());
        assertThat(reloaded.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(reloaded.getLocalDate()).isEqualTo(DATE);
        assertThat(reloaded.getTimeZoneId()).isEqualTo("UTC");
        assertThat(reloaded.getBucketStart()).isEqualTo(START);
        assertThat(reloaded.getBucketEnd()).isEqualTo(END);
        assertThat(reloaded.getFinalizedAt()).isEqualTo(END);
        assertThat(reloaded.getRefreshedAt()).isEqualTo(END);
        assertAggregate(reloaded,aggregate);
    }



    @ParameterizedTest
    @CsvSource({
            "Asia/Kathmandu,2026-01-16,2026-01-15T18:15:00Z,2026-01-16T18:15:00Z",
            "Europe/Berlin,2026-03-29,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z",
            "Europe/Berlin,2026-10-25,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z"
    })
    void preservesLocalDayMetadataAndActualUtcBoundaries(
            String zoneId,String date,String expectedStart,String expectedEnd) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,zoneId);
        LocalDate localDate = LocalDate.parse(date);
        ZoneId timeZone = ZoneId.of(zoneId);
        DailySensorSummary summary = persistSummary(sensor,localDate,timeZone);
        flushAndClear();

        DailySensorSummary reloaded = summaryRepository.findById(summary.getId()).orElseThrow();

        assertThat(reloaded.getLocalDate()).isEqualTo(localDate);
        assertThat(reloaded.getTimeZoneId()).isEqualTo(zoneId);
        assertThat(reloaded.getTimeZone()).isEqualTo(timeZone);
        assertThat(reloaded.getBucketStart()).isEqualTo(Instant.parse(expectedStart));
        assertThat(reloaded.getBucketEnd()).isEqualTo(Instant.parse(expectedEnd));
    }



    @Test
    void persistsRefreshesWithoutChangingLocalDayMetadataOrFinalizationTime() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary original = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        SensorSummaryAggregate replacement = SensorSummaryAggregate.numeric(
                3,MeasurementUnit.C,new BigDecimal("60.1234567890123456789"),10.0,30.0);
        Instant refreshedAt = END.plusSeconds(60);
        DailySensorSummary pending = summaryRepository.findById(original.getId()).orElseThrow();
        pending.refresh(replacement,refreshedAt);
        flushAndClear();

        DailySensorSummary reloaded = summaryRepository.findById(original.getId()).orElseThrow();

        assertThat(reloaded.getLocalDate()).isEqualTo(DATE);
        assertThat(reloaded.getTimeZoneId()).isEqualTo("UTC");
        assertThat(reloaded.getBucketStart()).isEqualTo(START);
        assertThat(reloaded.getBucketEnd()).isEqualTo(END);
        assertThat(reloaded.getFinalizedAt()).isEqualTo(END);
        assertThat(reloaded.getRefreshedAt()).isEqualTo(refreshedAt);
        assertAggregate(reloaded,replacement);

        SensorSummaryAggregate empty = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        reloaded.refresh(empty,refreshedAt.plusSeconds(60));
        flushAndClear();

        DailySensorSummary cleared = summaryRepository.findById(original.getId()).orElseThrow();

        assertAggregate(cleared,empty);
        assertThat(cleared.getFinalizedAt()).isEqualTo(END);
        assertThat(cleared.getRefreshedAt()).isEqualTo(refreshedAt.plusSeconds(60));
    }



    @Test
    void scopesBucketLookupAndFiltersOrderedStatisticsBySensorTimezoneAndOverlap() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        Sensor other = persistSensor("other",SensorType.TEMPERATURE,"UTC");

        DailySensorSummary second = persistSummary(target,DATE.plusDays(1),UTC);
        persistSummary(target,DATE.minusDays(1),UTC);
        persistSummary(target,DATE.plusDays(2),UTC);
        DailySensorSummary first = persistSummary(target,DATE,UTC);
        DailySensorSummary differentZone = persistSummary(target,DATE.plusDays(1),ZoneId.of("Europe/Istanbul"));
        DailySensorSummary foreign = persistSummary(other,DATE,UTC);
        flushAndClear();

        assertThat(summaryRepository.findBySensorIdAndBucketStart(target.getId(),START).map(DailySensorSummary::getId))
                .contains(first.getId());
        assertThat(summaryRepository.findBySensorIdAndBucketStart(other.getId(),START).map(DailySensorSummary::getId))
                .contains(foreign.getId());
        assertThat(summaryRepository.findBySensorIdAndBucketStart(-1L,START)).isEmpty();
        assertThat(summaryRepository.findBySensorIdAndBucketStart(target.getId(),START.plusSeconds(1))).isEmpty();

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),"UTC",START.minusSeconds(86400),START,END.plusSeconds(86400)))
                .extracting(DailySensorSummary::getId)
                .containsExactly(first.getId(),second.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),"UTC",START.plusSeconds(43200),START.plusSeconds(43200),END.plusSeconds(43200)))
                .extracting(DailySensorSummary::getId)
                .containsExactly(first.getId(),second.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),"UTC",END,START,END.plusSeconds(86400)))
                .extracting(DailySensorSummary::getId)
                .containsExactly(second.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),"Europe/Istanbul",START,START,END.plusSeconds(86400)))
                .extracting(DailySensorSummary::getId)
                .containsExactly(differentZone.getId());

        assertThat(summaryRepository.findForStatisticsRange(
                target.getId(),"Europe/Berlin",START,START,END)).isEmpty();
        assertThat(summaryRepository.findForStatisticsRange(-1L,"UTC",START,START,END)).isEmpty();
    }



    @Test
    void checksRetentionUsingInclusiveBucketEndsWithoutRequiringCheckpoints() {
        Sensor target = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        Sensor withoutSummaries = persistSensor("empty",SensorType.TEMPERATURE,"UTC");
        persistSummary(target,DATE,UTC);
        flushAndClear();

        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(target.getId(),END.minusNanos(1000))).isFalse();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(target.getId(),END)).isTrue();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(withoutSummaries.getId(),END)).isFalse();
        assertThat(summaryRepository.existsBySensorIdAndBucketEndLessThanEqual(-1L,END)).isFalse();
    }



    @Test
    void rejectsDuplicateSensorBucketStartsIndependentlyOfLocalDateThroughDirectSql() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary summary = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO daily_sensor_summaries (
                    sensor_id,local_date,time_zone_id,bucket_start,bucket_end,
                    source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,
                    true_sample_count,finalized_at,refreshed_at
                )
                SELECT sensor_id,local_date + 1,time_zone_id,bucket_start,bucket_end,
                       source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,
                       true_sample_count,finalized_at,refreshed_at
                FROM daily_sensor_summaries
                WHERE id = ?
                """,summary.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_daily_sensor_summaries_sensor_bucket_start");
    }



    @Test
    void rejectsDuplicateSensorLocalDatesAndZonesIndependentlyOfBucketStartThroughDirectSql() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary summary = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO daily_sensor_summaries (
                    sensor_id,local_date,time_zone_id,bucket_start,bucket_end,
                    source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,
                    true_sample_count,finalized_at,refreshed_at
                )
                SELECT sensor_id,local_date,time_zone_id,
                       bucket_start + INTERVAL '1 day',bucket_end + INTERVAL '1 day',
                       source_sample_count,unit,numeric_sum,numeric_minimum,numeric_maximum,
                       true_sample_count,finalized_at + INTERVAL '1 day',refreshed_at + INTERVAL '1 day'
                FROM daily_sensor_summaries
                WHERE id = ?
                """,summary.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_daily_sensor_summaries_sensor_local_date_zone");
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
            "time_zone_id = ''",
            "time_zone_id = '   '",
            "bucket_end = bucket_start",
            "bucket_end = bucket_start - INTERVAL '1 microsecond'",
            "refreshed_at = finalized_at - INTERVAL '1 microsecond'"
    })
    void rejectsInvalidSummaryValuesThroughDirectSql(String assignment) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary summary = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        assertSqlState("23514",() -> jdbcTemplate.update(
                "UPDATE daily_sensor_summaries SET " + assignment + " WHERE id = ?",summary.getId()));
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "sensor_id","local_date","time_zone_id","bucket_start","bucket_end",
            "source_sample_count","finalized_at","refreshed_at"
    })
    void rejectsNullRequiredColumnsThroughDirectSql(String column) {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary summary = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        assertSqlState("23502",() -> jdbcTemplate.update(
                "UPDATE daily_sensor_summaries SET " + column + " = NULL WHERE id = ?",summary.getId()));
    }



    @Test
    void rejectsAMissingSensorReferenceThroughDirectSql() {
        Sensor sensor = persistSensor("target",SensorType.TEMPERATURE,"UTC");
        DailySensorSummary summary = persistSummary(sensor,DATE,UTC);
        flushAndClear();

        assertSqlState("23503",() -> jdbcTemplate.update(
                "UPDATE daily_sensor_summaries SET sensor_id = ? WHERE id = ?",-1L,summary.getId()));
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



    private Sensor persistSensor(String name,SensorType type,String zoneId) {
        AppUser owner = appUserRepository.saveAndFlush(new AppUser(name + "@example.com","test-password-hash",CREATED_AT));
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window",zoneId,CREATED_AT));
    }



    private DailySensorSummary persistSummary(Sensor sensor,LocalDate date,ZoneId timeZone) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("30"),10.0,20.0);
        Instant finalizedAt = date.plusDays(1).atStartOfDay(timeZone).toInstant();
        return summaryRepository.saveAndFlush(DailySensorSummary.create(sensor,date,timeZone,aggregate,finalizedAt));
    }



    private void assertAggregate(DailySensorSummary summary,SensorSummaryAggregate expected) {
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