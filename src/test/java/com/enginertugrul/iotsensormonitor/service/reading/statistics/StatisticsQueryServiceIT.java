package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.HOURLY_TO_DAILY;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "app.sensor-data.lifecycle.raw-retention=P2D",
        "app.sensor-data.lifecycle.hourly-retention=P4D",
        "app.sensor-data.lifecycle.daily-retention=P730D",
        "app.sensor-data.statistics.chart-point-budget=48"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class StatisticsQueryServiceIT {

    private static final Instant CREATED_AT = Instant.parse("2025-01-01T00:00:00Z");
    private static final Instant DAY = Instant.parse("2026-01-14T00:00:00Z");

    @Autowired
    private StatisticsQueryService service;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private DailySensorSummaryRepository dailyRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private StatisticsAvailabilityResolver availabilityResolver;

    private final List<Long> ownerIds = new ArrayList<>();

    private TransactionTemplate transactions;
    private AppUser owner;



    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(20);
        owner = persistOwner();
    }



    @AfterEach
    void tearDown() {
        transactions.executeWithoutResult(status -> {
            for (Long ownerId : ownerIds) {
                jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
                jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
            }
        });
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void missingAndForeignSensorsHaveTheSameNotFoundBehavior(boolean export) {
        AppUser otherOwner = persistOwner();
        Sensor foreign = persistSensor(otherOwner,SensorType.TEMPERATURE,"UTC",null);
        Instant end = DAY.plusSeconds(3600);

        assertThatThrownBy(() -> readPoints(export,-1L,owner.getId(),DAY,end))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThatThrownBy(() -> readPoints(export,foreign.getId(),owner.getId(),DAY,end))
                .isExactlyInstanceOf(SensorNotFoundException.class)
                .hasMessage("Sensor not found")
                .hasNoCause();

        assertThat(readPoints(export,foreign.getId(),otherOwner.getId(),DAY,end)).hasSize(1)
                .allSatisfy(point -> assertThat(point.status()).isEqualTo(StatisticsPointStatus.NO_SAMPLES));
    }



    @Test
    void rawQueriesUseHalfOpenBoundsAndStableOrderingForTimestampTies() {
        Instant start = DAY.plusSeconds(3600);
        Instant end = start.plusSeconds(3600);
        Instant beforeStart = start.minusNanos(1000);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Europe/Istanbul",beforeStart);
        persistReading(sensor,90.0,false,beforeStart);
        SensorReading first = persistReading(sensor,10.0,false,start);
        SensorReading second = persistReading(sensor,20.0,false,start);
        persistReading(sensor,80.0,false,end);

        Sensor other = persistSensor(owner,SensorType.TEMPERATURE,"UTC",start);
        persistReading(other,70.0,false,start);

        SensorStatisticsSeriesDTO result = service.getSeries(
                sensor.getId(),owner.getId(),start,end,RAW,TemperatureUnit.FAHRENHEIT);

        assertThat(result.resolvedResolution()).isEqualTo(RAW);
        assertThat(result.sensor().timeZoneId()).isEqualTo("Europe/Istanbul");
        assertThat(result.sensor().displayUnit()).isEqualTo("FAHRENHEIT");
        assertThat(result.points()).extracting(StatisticsSeriesPointDTO::sourceReadingId)
                .containsExactly(first.getId(),second.getId());
        assertThat(result.points()).extracting(StatisticsSeriesPointDTO::recordedAt).containsExactly(start,start);
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
        assertThat(result.fullyCovered()).isTrue();
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(2);
        assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("118");
        assertThat(result.periodMetrics().numericMetrics().average()).isEqualByComparingTo("59");
        assertThat(readingRepository.findById(first.getId()).orElseThrow().getNumericValue()).isEqualTo(10.0);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void sensorsWithoutReadingHistoryRemainKnownEmptyAcrossStorageTiers(SensorType type) {
        Sensor sensor = persistSensor(owner,type,"UTC",null);
        Instant start = Instant.parse("2025-06-01T00:00:00Z");
        Instant end = start.plusSeconds(86400);

        for (StatisticsResolution resolution : List.of(RAW,HOURLY,DAILY)) {
            SensorStatisticsSeriesDTO result = service.getSeries(
                    sensor.getId(),owner.getId(),start,end,resolution,TemperatureUnit.CELSIUS);

            assertThat(result.resolvedResolution()).isEqualTo(resolution);
            assertThat(result.status()).isEqualTo(StatisticsRangeStatus.NO_SAMPLES);
            assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(false,false,false));
            assertThat(result.fullyCovered()).isTrue();
            assertThat(result.periodMetrics().available()).isTrue();
            assertThat(result.periodMetrics().sourceSampleCount()).isZero();
            assertThat(result.periodMetrics().numericMetrics()).isNull();

            int expectedPoints = resolution == RAW ? 0 : resolution == HOURLY ? 24 : 1;
            assertThat(result.points()).hasSize(expectedPoints)
                    .allSatisfy(point -> assertThat(point.status()).isEqualTo(StatisticsPointStatus.NO_SAMPLES));

            if (type == SensorType.MOTION) {
                assertThat(result.periodMetrics().motionMetrics()).isEqualTo(new StatisticsMotionMetricsDTO(0,0,0,null));
            } else {
                assertThat(result.periodMetrics().motionMetrics()).isNull();
            }
        }
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void dailyQueryCombinesDailyHourlyAndRawSourcesWithoutDoubleCounting(SensorType type) {
        Instant nextDay = DAY.plusSeconds(86400);
        Instant lastVerifiedHourEnd = nextDay.plusSeconds(11 * 3600);
        Instant requestedEnd = lastVerifiedHourEnd.plusSeconds(1800);
        Sensor sensor = persistSensor(owner,type,"UTC",DAY.plusSeconds(900));

        persistReading(sensor,10.0,true,DAY.plusSeconds(900));
        persistReading(sensor,20.0,false,nextDay.plusSeconds(900));
        persistReading(sensor,30.0,true,lastVerifiedHourEnd.plusSeconds(900));
        persistReading(sensor,100.0,false,requestedEnd);

        hourlyCoverage(sensor,DAY,lastVerifiedHourEnd,Map.of(
                DAY,sample(type,10.0,true),
                nextDay,sample(type,20.0,false)));
        dailySummary(sensor,LocalDate.of(2026,1,14),sample(type,10.0,true));

        SensorStatisticsSeriesDTO result = service.getSeries(
                sensor.getId(),owner.getId(),DAY,requestedEnd,DAILY,TemperatureUnit.CELSIUS);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.points()).hasSize(2);
        assertThat(result.points()).extracting(StatisticsSeriesPointDTO::status)
                .containsExactly(StatisticsPointStatus.COMPLETE,StatisticsPointStatus.PARTIAL);
        assertThat(result.points()).extracting(StatisticsSeriesPointDTO::sourceSampleCount).containsExactly(1L,2L);
        assertThat(result.points().get(0).bucketStart()).isEqualTo(DAY);
        assertThat(result.points().get(0).bucketEnd()).isEqualTo(nextDay);
        assertThat(result.points().get(1).bucketStart()).isEqualTo(nextDay);
        assertThat(result.points().get(1).bucketEnd()).isEqualTo(requestedEnd);
        assertThat(result.points().get(1).finalizedAt()).isNull();
        assertThat(result.points().get(1).refreshedAt()).isNull();
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.PARTIAL);
        assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(false,false,true));
        assertThat(result.fullyCovered()).isFalse();
        assertThat(result.periodMetrics().available()).isTrue();
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(3);

        if (type == SensorType.MOTION) {
            assertThat(result.periodMetrics().numericMetrics()).isNull();
            assertThat(result.periodMetrics().motionMetrics().totalSampleCount()).isEqualTo(3);
            assertThat(result.periodMetrics().motionMetrics().trueSampleCount()).isEqualTo(2);
            assertThat(result.periodMetrics().motionMetrics().falseSampleCount()).isEqualTo(1);
        } else {
            assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("60");
            assertThat(result.periodMetrics().numericMetrics().minimum()).isEqualByComparingTo("10");
            assertThat(result.periodMetrics().numericMetrics().average()).isEqualByComparingTo("20");
            assertThat(result.periodMetrics().numericMetrics().maximum()).isEqualByComparingTo("30");
            assertThat(result.periodMetrics().motionMetrics()).isNull();
        }
    }



    @Test
    void autoCanReconstructAPartialDayFromRawReadingsWhenClosedHourlyRollupsAreDelayed() {
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"UTC",DAY.plusSeconds(900));
        persistReading(sensor,10.0,false,DAY.plusSeconds(900));
        persistReading(sensor,30.0,false,DAY.plusSeconds(4500));
        Instant end = DAY.plusSeconds(7200);

        SensorStatisticsSeriesDTO hourly = service.getSeries(
                sensor.getId(),owner.getId(),DAY,end,HOURLY,TemperatureUnit.CELSIUS);

        assertThat(hourly.status()).isEqualTo(StatisticsRangeStatus.ROLLUP_DELAY);
        assertThat(hourly.points()).hasSize(2)
                .allSatisfy(point -> assertThat(point.status()).isEqualTo(StatisticsPointStatus.ROLLUP_DELAY));
        assertThat(hourly.periodMetrics()).isEqualTo(new StatisticsPeriodMetricsDTO(false,0,null,null));

        SensorStatisticsSeriesDTO automatic = service.getSeries(
                sensor.getId(),owner.getId(),DAY,end,AUTO,TemperatureUnit.CELSIUS);

        assertThat(automatic.resolvedResolution()).isEqualTo(DAILY);
        assertThat(automatic.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
        assertThat(automatic.fullyCovered()).isTrue();
        assertThat(automatic.points()).hasSize(1);
        assertThat(automatic.points().getFirst().bucketStart()).isEqualTo(DAY);
        assertThat(automatic.points().getFirst().bucketEnd()).isEqualTo(end);
        assertThat(automatic.periodMetrics().sourceSampleCount()).isEqualTo(2);
        assertThat(automatic.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("40");
        assertThat(automatic.periodMetrics().numericMetrics().average()).isEqualByComparingTo("20");
    }



    @Test
    void retainedDailyHistoryRemainsCompleteAfterRawAndHourlyHistoryExpires() {
        Instant start = Instant.parse("2026-01-05T00:00:00Z");
        Instant end = start.plusSeconds(86400);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"UTC",start.plusSeconds(900));
        persistReading(sensor,10.0,false,start.plusSeconds(900));
        persistReading(sensor,30.0,false,start.plusSeconds(4500));

        hourlyCoverage(sensor,start,end,Map.of(
                start,sample(SensorType.TEMPERATURE,10.0,false),
                start.plusSeconds(3600),sample(SensorType.TEMPERATURE,30.0,false)));
        dailySummary(sensor,LocalDate.of(2026,1,5),
                SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("40"),10.0,30.0));

        transactions.executeWithoutResult(status -> {
            jdbcTemplate.update("DELETE FROM sensor_readings WHERE sensor_id=?",sensor.getId());
            jdbcTemplate.update("DELETE FROM hourly_sensor_summaries WHERE sensor_id=?",sensor.getId());
        });

        for (StatisticsResolution resolution : List.of(RAW,HOURLY)) {
            SensorStatisticsSeriesDTO expired = service.getSeries(
                    sensor.getId(),owner.getId(),start,end,resolution,TemperatureUnit.CELSIUS);

            assertThat(expired.status()).isEqualTo(StatisticsRangeStatus.EXPIRED);
            assertThat(expired.fullyCovered()).isFalse();
            assertThat(expired.periodMetrics()).isEqualTo(new StatisticsPeriodMetricsDTO(false,0,null,null));

            if (resolution == RAW) {
                assertThat(expired.points()).isEmpty();
            } else {
                assertThat(expired.points()).hasSize(24)
                        .allSatisfy(point -> assertThat(point.status()).isEqualTo(StatisticsPointStatus.EXPIRED));
            }
        }

        for (StatisticsResolution resolution : List.of(DAILY,AUTO)) {
            SensorStatisticsSeriesDTO retained = service.getSeries(
                    sensor.getId(),owner.getId(),start,end,resolution,TemperatureUnit.CELSIUS);

            assertThat(retained.resolvedResolution()).isEqualTo(DAILY);
            assertThat(retained.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
            assertThat(retained.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(false,false,false));
            assertThat(retained.fullyCovered()).isTrue();
            assertThat(retained.points()).hasSize(1);
            assertThat(retained.periodMetrics().sourceSampleCount()).isEqualTo(2);
            assertThat(retained.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("40");
            assertThat(retained.periodMetrics().numericMetrics().average()).isEqualByComparingTo("20");
        }
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void queryKeepsOneDatabaseSnapshotWhileAnotherTransactionCommits(boolean export) {
        Instant firstHourEnd = DAY.plusSeconds(3600);
        Instant end = DAY.plusSeconds(7200);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"UTC",DAY.plusSeconds(900));
        persistReading(sensor,10.0,false,DAY.plusSeconds(900));
        hourlyCoverage(sensor,DAY,firstHourEnd,Map.of(DAY,sample(SensorType.TEMPERATURE,10.0,false)));
        AtomicBoolean changed = new AtomicBoolean();

        doAnswer(invocation -> {
            StatisticsAvailabilitySnapshot snapshot = (StatisticsAvailabilitySnapshot) invocation.callRealMethod();

            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SHOW transaction_isolation",String.class)).isEqualTo("repeatable read");
            assertThat(jdbcTemplate.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");

            if (changed.compareAndSet(false,true)) {
                Integer readerConnection = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);

                transactions.executeWithoutResult(status -> {
                    assertThat(TransactionSynchronizationManager.isCurrentTransactionReadOnly()).isFalse();
                    assertThat(jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class)).isNotEqualTo(readerConnection);

                    Sensor managedSensor = sensorRepository.findById(sensor.getId()).orElseThrow();
                    persistReading(managedSensor,20.0,false,DAY.plusSeconds(1800));
                    persistReading(managedSensor,40.0,false,firstHourEnd.plusSeconds(900));

                    HourlySensorSummary first = hourlyRepository.findBySensorIdAndBucketStart(sensor.getId(),DAY).orElseThrow();
                    first.refresh(SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("30"),10.0,20.0),TEST_INSTANT);
                    hourlyRepository.saveAndFlush(first);
                    hourlyRepository.saveAndFlush(HourlySensorSummary.create(
                            managedSensor,firstHourEnd,sample(SensorType.TEMPERATURE,40.0,false),TEST_INSTANT));

                    SensorRollupCheckpoint checkpoint = checkpointRepository.findBySensorIdAndStage(sensor.getId(),RAW_TO_HOURLY).orElseThrow();
                    checkpoint.recordAttempt(firstHourEnd,TEST_INSTANT);
                    checkpoint.advanceContiguously(firstHourEnd,end,TEST_INSTANT);
                    checkpointRepository.saveAndFlush(checkpoint);
                });
            }

            return snapshot;
        }).when(availabilityResolver).resolve(any(Sensor.class),eq(TEST_INSTANT));

        List<StatisticsSeriesPointDTO> original = readPoints(export,sensor.getId(),owner.getId(),DAY,end);

        assertThat(changed.get()).isTrue();
        assertThat(original).hasSize(2);
        assertThat(original.get(0).status()).isEqualTo(StatisticsPointStatus.COMPLETE);
        assertThat(original.get(0).sourceSampleCount()).isEqualTo(1L);
        assertThat(original.get(0).numericMetrics().sum()).isEqualByComparingTo("10");
        assertThat(original.get(1).status()).isEqualTo(StatisticsPointStatus.ROLLUP_DELAY);
        assertThat(original.get(1).sourceSampleCount()).isNull();
        assertThat(original.get(1).numericMetrics()).isNull();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        List<StatisticsSeriesPointDTO> subsequent = readPoints(export,sensor.getId(),owner.getId(),DAY,end);

        assertThat(subsequent).hasSize(2)
                .allSatisfy(point -> assertThat(point.status()).isEqualTo(StatisticsPointStatus.COMPLETE));
        assertThat(subsequent.get(0).sourceSampleCount()).isEqualTo(2L);
        assertThat(subsequent.get(0).numericMetrics().sum()).isEqualByComparingTo("30");
        assertThat(subsequent.get(1).sourceSampleCount()).isEqualTo(1L);
        assertThat(subsequent.get(1).numericMetrics().sum()).isEqualByComparingTo("40");
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }



    private AppUser persistOwner() {
        AppUser saved = userRepository.saveAndFlush(new AppUser(
                "statistics-" + UUID.randomUUID() + "@example.com","test-password-hash",CREATED_AT));
        ownerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser sensorOwner,SensorType type,String timezone,Instant firstReadingAt) {
        Sensor sensor = new Sensor(
                sensorOwner,type,"Statistics " + UUID.randomUUID(),"Istanbul","Kadikoy","Window",timezone,CREATED_AT);

        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,TEST_INSTANT);
        }

        return sensorRepository.saveAndFlush(sensor);
    }



    private SensorReading persistReading(Sensor sensor,double value,boolean detected,Instant recordedAt) {
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,value,recordedAt);
            case MOTION -> SensorReading.motion(sensor,detected,recordedAt);
        };

        return readingRepository.saveAndFlush(reading);
    }



    private void hourlyCoverage(Sensor sensor,Instant start,Instant end,Map<Instant,SensorSummaryAggregate> populatedHours) {
        transactions.executeWithoutResult(status -> {
            Sensor managedSensor = sensorRepository.findById(sensor.getId()).orElseThrow();
            SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(managedSensor,RAW_TO_HOURLY,start,TEST_INSTANT);

            for (Instant hour = start; hour.isBefore(end); hour = hour.plusSeconds(3600)) {
                SensorSummaryAggregate aggregate = populatedHours.getOrDefault(hour,empty(managedSensor.getType()));
                hourlyRepository.save(HourlySensorSummary.create(managedSensor,hour,aggregate,TEST_INSTANT));
                checkpoint.recordAttempt(hour,TEST_INSTANT);
                checkpoint.advanceContiguously(hour,hour.plusSeconds(3600),TEST_INSTANT);
            }

            checkpointRepository.saveAndFlush(checkpoint);
        });
    }



    private void dailySummary(Sensor sensor,LocalDate date,SensorSummaryAggregate aggregate) {
        transactions.executeWithoutResult(status -> {
            Sensor managedSensor = sensorRepository.findById(sensor.getId()).orElseThrow();
            ZoneId zone = ZoneId.of(managedSensor.getTimezone());
            Instant start = date.atStartOfDay(zone).toInstant();
            Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
            dailyRepository.save(DailySensorSummary.create(managedSensor,date,zone,aggregate,TEST_INSTANT));

            SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(managedSensor,HOURLY_TO_DAILY,start,TEST_INSTANT);
            checkpoint.recordAttempt(start,TEST_INSTANT);
            checkpoint.advanceContiguously(start,end,TEST_INSTANT);
            checkpointRepository.saveAndFlush(checkpoint);
        });
    }



    private List<StatisticsSeriesPointDTO> readPoints(boolean export,Long sensorId,Long ownerId,Instant start,Instant end) {
        if (export) {
            return service.getSummaryExport(sensorId,ownerId,start,end,HOURLY,TemperatureUnit.CELSIUS).rows();
        }

        return service.getSeries(sensorId,ownerId,start,end,HOURLY,TemperatureUnit.CELSIUS).points();
    }



    private static SensorSummaryAggregate sample(SensorType type,double value,boolean detected) {
        if (type == SensorType.MOTION) {
            return SensorSummaryAggregate.booleanSamples(1,detected ? 1 : 0);
        }

        MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
        return SensorSummaryAggregate.numeric(1,unit,BigDecimal.valueOf(value),value,value);
    }



    private static SensorSummaryAggregate empty(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
            case HUMIDITY -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT);
            case MOTION -> SensorSummaryAggregate.emptyBoolean();
        };
    }
}