package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;


@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Transactional
class SensorReadingRepositoryIT {

    private static final Instant RANGE_START = CREATED_AT.plusSeconds(3600);
    private static final Instant RANGE_END = RANGE_START.plusSeconds(60);

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository sensorReadingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;



    @Test
    void returnsTheLatestTenReadingsWithDescendingIdOrderForTimestampTies() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor sibling = persistSensor(owner,SensorType.TEMPERATURE,"Sibling");
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign");

        SensorReading newest = persistTemperatureReading(sensor,30.0,RANGE_START.plusSeconds(1));
        List<Long> tiedIds = new ArrayList<>();

        for (int index = 0; index < 11; index++) {
            tiedIds.add(persistTemperatureReading(sensor,20.0 + index,RANGE_START).getId());
        }

        persistTemperatureReading(sensor,10.0,RANGE_START.minusSeconds(1));
        persistTemperatureReading(sibling,40.0,RANGE_START.plusSeconds(10));
        persistTemperatureReading(foreign,50.0,RANGE_START.plusSeconds(20));
        flushAndClear();

        List<Long> expectedIds = new ArrayList<>();
        expectedIds.add(newest.getId());
        expectedIds.addAll(tiedIds.reversed().subList(0,9));

        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(sensor.getId(),owner.getId()))
                .extracting(SensorReading::getId)
                .containsExactlyElementsOf(expectedIds);
    }



    @Test
    void returnsNoRecentReadingsForForeignMissingOrEmptySensors() {
        AppUser owner = persistUser("owner@example.com");
        AppUser otherOwner = persistUser("other@example.com");
        Sensor owned = persistSensor(owner,SensorType.TEMPERATURE,"Owned");
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign");
        Sensor empty = persistSensor(owner,SensorType.TEMPERATURE,"Empty");

        persistTemperatureReading(owned,20.0,RANGE_START);
        persistTemperatureReading(foreign,25.0,RANGE_START);
        flushAndClear();

        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(foreign.getId(),owner.getId())).isEmpty();
        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(owned.getId(),otherOwner.getId())).isEmpty();
        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(-1L,owner.getId())).isEmpty();
        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(owned.getId(),-1L)).isEmpty();
        assertThat(sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(empty.getId(),owner.getId())).isEmpty();
    }



    @Test
    void paginatesAHalfOpenRangeInAscendingTimestampAndIdOrder() {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor other = persistSensor(owner,SensorType.TEMPERATURE,"Other");

        SensorReading last = persistTemperatureReading(sensor,24.0,RANGE_END.minusNanos(1000));
        SensorReading first = persistTemperatureReading(sensor,20.0,RANGE_START);
        SensorReading firstTie = persistTemperatureReading(sensor,21.0,RANGE_START.plusSeconds(10));
        SensorReading secondTie = persistTemperatureReading(sensor,22.0,RANGE_START.plusSeconds(10));

        persistTemperatureReading(sensor,10.0,RANGE_START.minusNanos(1000));
        persistTemperatureReading(sensor,30.0,RANGE_END);
        persistTemperatureReading(other,50.0,RANGE_START);
        flushAndClear();

        Slice<SensorReading> firstPage = sensorReadingRepository.findForStatisticsRange(sensor.getId(),RANGE_START,RANGE_END,PageRequest.of(0,2));

        assertThat(firstPage.getContent()).extracting(SensorReading::getId).containsExactly(first.getId(),firstTie.getId());
        assertThat(firstPage.hasPrevious()).isFalse();
        assertThat(firstPage.hasNext()).isTrue();

        Slice<SensorReading> secondPage = sensorReadingRepository.findForStatisticsRange(sensor.getId(),RANGE_START,RANGE_END,firstPage.nextPageable());

        assertThat(secondPage.getContent()).extracting(SensorReading::getId).containsExactly(secondTie.getId(),last.getId());
        assertThat(secondPage.hasPrevious()).isTrue();
        assertThat(secondPage.hasNext()).isFalse();

        Slice<SensorReading> beyondLastPage = sensorReadingRepository.findForStatisticsRange(sensor.getId(),RANGE_START,RANGE_END,PageRequest.of(2,2));

        assertThat(beyondLastPage.getContent()).isEmpty();
        assertThat(beyondLastPage.hasNext()).isFalse();
        assertThat(sensorReadingRepository.findForStatisticsRange(sensor.getId(),RANGE_START,RANGE_START,PageRequest.of(0,2)).getContent()).isEmpty();
        assertThat(sensorReadingRepository.findForStatisticsRange(-1L,RANGE_START,RANGE_END,PageRequest.of(0,2)).getContent()).isEmpty();
    }



    @Test
    void checksForReadingsStrictlyBeforeTheBoundaryWithinTheRequestedSensor() {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor other = persistSensor(owner,SensorType.TEMPERATURE,"Other");

        persistTemperatureReading(sensor,20.0,RANGE_START);
        persistTemperatureReading(other,25.0,RANGE_START.minusNanos(1000));
        flushAndClear();

        assertThat(sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(sensor.getId(),RANGE_START)).isFalse();
        assertThat(sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(sensor.getId(),RANGE_START.plusNanos(1000))).isTrue();
        assertThat(sensorReadingRepository.existsBySensorIdAndRecordedAtBefore(-1L,RANGE_END)).isFalse();
    }



    @Test
    void aggregatesNumericReadingsUsingDecimalSumsWithinTheSensorAndHalfOpenRange() {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor other = persistSensor(owner,SensorType.TEMPERATURE,"Other");

        persistTemperatureReading(sensor,0.1,RANGE_START);
        persistTemperatureReading(sensor,0.2,RANGE_START);
        persistTemperatureReading(sensor,100.0,RANGE_START.minusNanos(1000));
        persistTemperatureReading(sensor,200.0,RANGE_END);
        persistTemperatureReading(other,50.0,RANGE_START);
        flushAndClear();

        RawSensorReadingAggregateProjection aggregate = sensorReadingRepository.aggregateForSummaryRange(sensor.getId(),RANGE_START,RANGE_END);

        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(2);
        assertThat(aggregate.getNumericSum()).isEqualByComparingTo("0.3");
        assertThat(aggregate.getNumericMinimum()).isEqualTo(0.1);
        assertThat(aggregate.getNumericMaximum()).isEqualTo(0.2);
        assertThat(aggregate.getTrueSampleCount()).isZero();
    }



    @ParameterizedTest
    @ValueSource(ints = {0,2})
    void aggregatesMotionReadingsWithoutNumericMetrics(int trueSampleCount) {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.MOTION,"Target");
        Sensor other = persistSensor(owner,SensorType.MOTION,"Other");

        for (int index = 0; index < 3; index++) {
            sensorReadingRepository.save(SensorReading.motion(sensor,index < trueSampleCount,RANGE_START.plusSeconds(index * 10L)));
        }

        sensorReadingRepository.save(SensorReading.motion(sensor,true,RANGE_START.minusNanos(1000)));
        sensorReadingRepository.save(SensorReading.motion(sensor,true,RANGE_END));
        sensorReadingRepository.save(SensorReading.motion(other,true,RANGE_START));
        flushAndClear();

        RawSensorReadingAggregateProjection aggregate = sensorReadingRepository.aggregateForSummaryRange(sensor.getId(),RANGE_START,RANGE_END);

        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getSourceSampleCount()).isEqualTo(3);
        assertThat(aggregate.getTrueSampleCount()).isEqualTo(trueSampleCount);
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }



    @Test
    void returnsZeroCountsAndNullNumericMetricsForEmptyAggregates() {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        Sensor other = persistSensor(owner,SensorType.TEMPERATURE,"Other");

        persistTemperatureReading(sensor,20.0,RANGE_END);
        persistTemperatureReading(other,25.0,RANGE_START);
        flushAndClear();

        assertEmptyAggregate(sensorReadingRepository.aggregateForSummaryRange(sensor.getId(),RANGE_START,RANGE_END));
        assertEmptyAggregate(sensorReadingRepository.aggregateForSummaryRange(sensor.getId(),RANGE_END,RANGE_END));
        assertEmptyAggregate(sensorReadingRepository.aggregateForSummaryRange(-1L,RANGE_START,RANGE_END));
    }



    @ParameterizedTest
    @MethodSource("validReadingValues")
    void acceptsAndReloadsValidReadingShapesAndBoundaryValues(SensorType type,Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,type,"Target");

        Long readingId = insertReading(sensor.getId(),numericValue,booleanValue,unit == null ? null : unit.name(),RANGE_START);
        flushAndClear();

        SensorReading reloaded = sensorReadingRepository.findById(readingId).orElseThrow();

        assertThat(readingId).isPositive();
        assertThat(reloaded.getSensor().getId()).isEqualTo(sensor.getId());
        assertThat(reloaded.getNumericValue()).isEqualTo(numericValue);
        assertThat(reloaded.getBooleanValue()).isEqualTo(booleanValue);
        assertThat(reloaded.getUnit()).isEqualTo(unit);
        assertThat(reloaded.getRecordedAt()).isEqualTo(RANGE_START);
    }



    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidReadingValues")
    void rejectsInvalidReadingValuesThroughDirectSql(String description,SensorType type,Double numericValue,Boolean booleanValue,String unit) {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,type,"Target");

        DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class,
                () -> insertReading(sensor.getId(),numericValue,booleanValue,unit,RANGE_START));

        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) exception.getMostSpecificCause();
        assertThat(sqlException.getSQLState()).isEqualTo("23514");
    }



    @Test
    void rejectsReadingsForMissingSensorsThroughDirectSql() {
        assertThatThrownBy(() -> insertReading(-1L,20.0,null,"C",RANGE_START))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_sensor_readings_sensor");
    }



    @Test
    void rejectsReadingsWithoutRecordedAtThroughDirectSql() {
        AppUser owner = persistUser("owner@example.com");
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Target");

        assertThatThrownBy(() -> insertReading(sensor.getId(),20.0,null,"C",null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("recorded_at");
    }



    private static Stream<Arguments> validReadingValues() {
        return Stream.of(
                Arguments.of(SensorType.TEMPERATURE,-273.15,null,MeasurementUnit.C),
                Arguments.of(SensorType.HUMIDITY,0.0,null,MeasurementUnit.PERCENT),
                Arguments.of(SensorType.HUMIDITY,100.0,null,MeasurementUnit.PERCENT),
                Arguments.of(SensorType.MOTION,null,true,null),
                Arguments.of(SensorType.MOTION,null,false,null));
    }



    private static Stream<Arguments> invalidReadingValues() {
        return Stream.of(
                Arguments.of("both numeric and boolean values",SensorType.TEMPERATURE,20.0,true,"C"),
                Arguments.of("neither numeric nor boolean value",SensorType.TEMPERATURE,null,null,null),
                Arguments.of("numeric value without unit",SensorType.TEMPERATURE,20.0,null,null),
                Arguments.of("boolean value with unit",SensorType.MOTION,null,true,"C"),
                Arguments.of("unsupported unit",SensorType.TEMPERATURE,20.0,null,"F"),
                Arguments.of("NaN numeric value",SensorType.TEMPERATURE,Double.NaN,null,"C"),
                Arguments.of("positive infinite numeric value",SensorType.TEMPERATURE,Double.POSITIVE_INFINITY,null,"C"),
                Arguments.of("negative infinite numeric value",SensorType.TEMPERATURE,Double.NEGATIVE_INFINITY,null,"C"),
                Arguments.of("temperature below absolute zero",SensorType.TEMPERATURE,-273.16,null,"C"),
                Arguments.of("humidity below zero",SensorType.HUMIDITY,-0.01,null,"PERCENT"),
                Arguments.of("humidity above one hundred",SensorType.HUMIDITY,100.01,null,"PERCENT"));
    }



    private AppUser persistUser(String email) {
        return appUserRepository.saveAndFlush(new AppUser(email,"test-password-hash",CREATED_AT));
    }



    private Sensor persistSensor(AppUser owner,SensorType type,String name) {
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT));
    }



    private SensorReading persistTemperatureReading(Sensor sensor,double value,Instant recordedAt) {
        return sensorReadingRepository.save(SensorReading.temperature(sensor,value,recordedAt));
    }



    private Long insertReading(Long sensorId,Double numericValue,Boolean booleanValue,String unit,Instant recordedAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO sensor_readings (sensor_id,numeric_value,boolean_value,unit,recorded_at)
                VALUES (?,?,?,?,?)
                RETURNING id
                """,Long.class,sensorId,numericValue,booleanValue,unit,recordedAt == null ? null : Timestamp.from(recordedAt));
    }



    private void assertEmptyAggregate(RawSensorReadingAggregateProjection aggregate) {
        assertThat(aggregate).isNotNull();
        assertThat(aggregate.getSourceSampleCount()).isZero();
        assertThat(aggregate.getTrueSampleCount()).isZero();
        assertThat(aggregate.getNumericSum()).isNull();
        assertThat(aggregate.getNumericMinimum()).isNull();
        assertThat(aggregate.getNumericMaximum()).isNull();
    }


    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}