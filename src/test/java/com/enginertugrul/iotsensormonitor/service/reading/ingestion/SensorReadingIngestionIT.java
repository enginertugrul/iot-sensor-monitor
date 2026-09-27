package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import com.enginertugrul.iotsensormonitor.service.alert.AlertEvaluationService;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class SensorReadingIngestionIT {

    private static final Instant SENSOR_CREATED_AT = TEST_INSTANT.minus(3,ChronoUnit.DAYS);
    private static final Instant BEFORE_INGESTION = TEST_INSTANT.minusSeconds(60);
    private static final Instant EXISTING_READING_AT = TEST_INSTANT.minusSeconds(7200);
    private static final Instant RECORDED_AT = Instant.parse("2026-01-15T07:30:00.123456Z");
    private static final Instant REQUESTED_AT = RECORDED_AT.plusNanos(789);
    private static final Instant HOUR_START = Instant.parse("2026-01-15T07:00:00Z");
    private static final Instant LOCAL_DAY_START = Instant.parse("2026-01-14T08:00:00Z");

    @Autowired
    private SensorReadingIngestionService service;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private SensorIngestionTokenGenerator tokenGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean(enforceOverride = true)
    private AlertEvaluationService alertEvaluationService;

    private AppUser owner;


    @BeforeEach
    void setUp() {
        owner = appUserRepository.saveAndFlush(new AppUser("reading-ingestion-" + UUID.randomUUID() + "@example.com","test-password-hash",SENSOR_CREATED_AT));
    }


    @AfterEach
    void tearDown() {
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",owner.getId());
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",owner.getId());
        }
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,23.5,,C",
            "HUMIDITY,58.0,,PERCENT",
            "MOTION,,true,",
            "MOTION,,false,"
    })
    void commitsTypedReadingAndFirstReadingHistoryWithoutCreatingCheckpoints(SensorType type,Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        Fixture fixture = persistSensor(type,"UTC");

        ingest(fixture,numericValue,booleanValue);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        List<SensorReading> readings = readingsFor(fixture);
        assertThat(readings).hasSize(1);

        SensorReading reading = readings.getFirst();
        assertThat(reading.getId()).isPositive();
        assertThat(reading.getNumericValue()).isEqualTo(numericValue);
        assertThat(reading.getBooleanValue()).isEqualTo(booleanValue);
        assertThat(reading.getUnit()).isEqualTo(unit);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);

        Sensor reloaded = sensorRepository.findById(fixture.sensor().getId()).orElseThrow();
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(RECORDED_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(reloaded.getCreatedAt()).isEqualTo(SENSOR_CREATED_AT);
        assertThat(reloaded.getIngestionTokenHash()).isEqualTo(fixture.sensor().getIngestionTokenHash());
        assertThat(checkpointRows(reloaded.getId())).isEmpty();

        ArgumentCaptor<SensorReading> captor = ArgumentCaptor.forClass(SensorReading.class);
        verify(alertEvaluationService).evaluateReading(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(reading.getId());
        assertThat(captor.getValue().getSensor().getId()).isEqualTo(reloaded.getId());
        assertThat(captor.getValue().getRecordedAt()).isEqualTo(RECORDED_AT);
        verifyNoMoreInteractions(alertEvaluationService);
    }



    @ParameterizedTest
    @ValueSource(longs = {-60,0,60})
    void commitsEveryReadingButChangesHistoryOnlyForAnEarlierTimestamp(long offsetSeconds) {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE,"UTC");
        seedHistory(fixture);
        Instant candidate = EXISTING_READING_AT.plusSeconds(offsetSeconds);

        service.ingestTemperature(fixture.rawToken(),23.5,candidate);

        Sensor reloaded = sensorRepository.findById(fixture.sensor().getId()).orElseThrow();
        assertThat(reloaded.getFirstReadingAt()).isEqualTo(offsetSeconds < 0 ? candidate : EXISTING_READING_AT);
        assertThat(reloaded.getUpdatedAt()).isEqualTo(offsetSeconds < 0 ? TEST_INSTANT : BEFORE_INGESTION);
        assertThat(readingsFor(fixture)).hasSize(2)
                .extracting(SensorReading::getRecordedAt)
                .containsExactlyInAnyOrder(EXISTING_READING_AT,candidate);
        verify(alertEvaluationService).evaluateReading(any(SensorReading.class));
    }



    @ParameterizedTest
    @CsvSource({
            "270,3,true,true",
            "210,3,true,false",
            "150,3,false,false",
            "270,5,false,true"
    })
    void commitsOnlyTheCheckpointInvalidationsAffectedByTheReading(int minutesBeforeNow,int hourlyCoverageHoursBeforeNow,boolean invalidatesHourly,boolean invalidatesDaily) {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE,"America/Los_Angeles");
        seedHistory(fixture);
        Instant recordedAt = TEST_INSTANT.minusSeconds(minutesBeforeNow * 60L);
        Instant hourlyEnd = TEST_INSTANT.minusSeconds(hourlyCoverageHoursBeforeNow * 3600L);
        Instant dailyEnd = LOCAL_DAY_START.plusSeconds(86400);
        SensorRollupCheckpoint hourly = persistCompletedCheckpoint(fixture.sensor(),RollupStage.RAW_TO_HOURLY,HOUR_START.minusSeconds(3600),hourlyEnd);
        SensorRollupCheckpoint daily = persistCompletedCheckpoint(fixture.sensor(),RollupStage.HOURLY_TO_DAILY,LOCAL_DAY_START.minusSeconds(86400),dailyEnd);

        service.ingestTemperature(fixture.rawToken(),23.5,recordedAt);

        SensorRollupCheckpoint reloadedHourly = checkpointRepository.findById(hourly.getId()).orElseThrow();
        SensorRollupCheckpoint reloadedDaily = checkpointRepository.findById(daily.getId()).orElseThrow();

        if (invalidatesHourly) {
            assertInvalidated(reloadedHourly,hourly.getCoverageStartedAt(),recordedAt.truncatedTo(ChronoUnit.HOURS));
        } else {
            assertCheckpointUnchanged(reloadedHourly,hourly);
        }

        if (invalidatesDaily) {
            assertInvalidated(reloadedDaily,daily.getCoverageStartedAt(),LOCAL_DAY_START);
        } else {
            assertCheckpointUnchanged(reloadedDaily,daily);
        }

        Sensor reloadedSensor = sensorRepository.findById(fixture.sensor().getId()).orElseThrow();
        assertThat(reloadedSensor.getFirstReadingAt()).isEqualTo(recordedAt);
        assertThat(reloadedSensor.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(readingsFor(fixture)).hasSize(2);
        verify(alertEvaluationService).evaluateReading(any(SensorReading.class));
    }



    @Test
    void commitsEarlierCoverageStartsWhenReadingPredatesInitializedCheckpoints() {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE,"America/Los_Angeles");
        seedHistory(fixture);
        SensorRollupCheckpoint hourly = checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(fixture.sensor(),RollupStage.RAW_TO_HOURLY,HOUR_START.plusSeconds(3600),BEFORE_INGESTION));
        SensorRollupCheckpoint daily = checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(fixture.sensor(),RollupStage.HOURLY_TO_DAILY,LOCAL_DAY_START.plusSeconds(86400),BEFORE_INGESTION));

        service.ingestTemperature(fixture.rawToken(),23.5,REQUESTED_AT);

        assertInvalidated(checkpointRepository.findById(hourly.getId()).orElseThrow(),HOUR_START,HOUR_START);
        assertInvalidated(checkpointRepository.findById(daily.getId()).orElseThrow(),LOCAL_DAY_START,LOCAL_DAY_START);
        assertThat(sensorRepository.findById(fixture.sensor().getId()).orElseThrow().getFirstReadingAt()).isEqualTo(RECORDED_AT);
        assertThat(readingsFor(fixture)).hasSize(2);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rollsBackFlushedReadingHistoryAndBothCheckpointsWhenAlertEvaluationFails(SensorType type) {
        Fixture fixture = persistSensor(type,"America/Los_Angeles");
        seedHistory(fixture);
        persistCompletedCheckpoint(fixture.sensor(),RollupStage.RAW_TO_HOURLY,HOUR_START.minusSeconds(3600),HOUR_START.plusSeconds(7200));
        persistCompletedCheckpoint(fixture.sensor(),RollupStage.HOURLY_TO_DAILY,LOCAL_DAY_START.minusSeconds(86400),LOCAL_DAY_START.plusSeconds(86400));

        Long sensorId = fixture.sensor().getId();
        Map<String,Object> sensorBefore = sensorRow(sensorId);
        List<Map<String,Object>> readingsBefore = readingRows(sensorId);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensorId);
        IllegalStateException failure = new IllegalStateException("Fail after flushing ingestion changes");

        doAnswer(invocation -> {
            SensorReading reading = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(entityManager.isJoinedToTransaction()).isTrue();
            assertThat(reading.getId()).isPositive();
            assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);

            entityManager.flush();

            assertThat(readingRows(sensorId)).hasSize(2);
            assertThat(jdbcTemplate.queryForObject("SELECT first_reading_at FROM sensors WHERE id=?",Timestamp.class,sensorId).toInstant())
                    .isEqualTo(RECORDED_AT);
            assertThat(coveredUntil(sensorId,RollupStage.RAW_TO_HOURLY)).isEqualTo(HOUR_START);
            assertThat(coveredUntil(sensorId,RollupStage.HOURLY_TO_DAILY)).isEqualTo(LOCAL_DAY_START);
            throw failure;
        }).when(alertEvaluationService).evaluateReading(any(SensorReading.class));

        assertThatThrownBy(() -> ingest(fixture,23.5,true)).isSameAs(failure);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(sensorRow(sensorId)).isEqualTo(sensorBefore);
        assertThat(readingRows(sensorId)).containsExactlyElementsOf(readingsBefore);
        assertThat(checkpointRows(sensorId)).containsExactlyElementsOf(checkpointsBefore);
        verify(alertEvaluationService).evaluateReading(any(SensorReading.class));
    }



    @Test
    void restoresNullFirstReadingWhenTheFirstIngestionRollsBack() {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE,"UTC");
        Long sensorId = fixture.sensor().getId();
        Map<String,Object> sensorBefore = sensorRow(sensorId);
        IllegalStateException failure = new IllegalStateException("Reject first ingestion during alert evaluation");

        doAnswer(invocation -> {
            entityManager.flush();
            assertThat(readingRows(sensorId)).hasSize(1);
            assertThat(jdbcTemplate.queryForObject("SELECT first_reading_at FROM sensors WHERE id=?",Timestamp.class,sensorId).toInstant())
                    .isEqualTo(RECORDED_AT);
            throw failure;
        }).when(alertEvaluationService).evaluateReading(any(SensorReading.class));

        assertThatThrownBy(() -> service.ingestTemperature(fixture.rawToken(),23.5,REQUESTED_AT)).isSameAs(failure);

        assertThat(sensorRow(sensorId)).isEqualTo(sensorBefore);
        assertThat(sensorRepository.findById(sensorId).orElseThrow().getFirstReadingAt()).isNull();
        assertThat(readingRows(sensorId)).isEmpty();
        assertThat(checkpointRows(sensorId)).isEmpty();
    }



    private Fixture persistSensor(SensorType type,String timezone) {
        GeneratedSensorIngestionToken token = tokenGenerator.generate();
        Sensor sensor = new Sensor(owner,type,"Living room","Istanbul","Kadikoy","Window",timezone,SENSOR_CREATED_AT);
        sensor.assignIngestionTokenHash(token.tokenHash(),SENSOR_CREATED_AT);
        return new Fixture(sensorRepository.saveAndFlush(sensor),token.rawToken());
    }



    private void seedHistory(Fixture fixture) {
        Sensor sensor = fixture.sensor();
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,20.0,EXISTING_READING_AT);
            case HUMIDITY -> SensorReading.humidity(sensor,50.0,EXISTING_READING_AT);
            case MOTION -> SensorReading.motion(sensor,false,EXISTING_READING_AT);
        };
        readingRepository.saveAndFlush(reading);
        sensor.recordFirstReading(EXISTING_READING_AT,BEFORE_INGESTION);
        sensorRepository.saveAndFlush(sensor);
    }



    private SensorRollupCheckpoint persistCompletedCheckpoint(Sensor sensor,RollupStage stage,Instant start,Instant end) {
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,start,BEFORE_INGESTION);
        long bucketSeconds = stage == RollupStage.RAW_TO_HOURLY ? 3600 : 86400;
        while (checkpoint.getCoveredUntil().isBefore(end)) {
            Instant bucketStart = checkpoint.getCoveredUntil();
            checkpoint.recordAttempt(bucketStart,BEFORE_INGESTION);
            checkpoint.advanceContiguously(bucketStart,bucketStart.plusSeconds(bucketSeconds),BEFORE_INGESTION);
        }
        return checkpointRepository.saveAndFlush(checkpoint);
    }



    private void ingest(Fixture fixture,Double numericValue,Boolean booleanValue) {
        switch (fixture.sensor().getType()) {
            case TEMPERATURE -> service.ingestTemperature(fixture.rawToken(),numericValue,REQUESTED_AT);
            case HUMIDITY -> service.ingestHumidity(fixture.rawToken(),numericValue,REQUESTED_AT);
            case MOTION -> service.ingestMotion(fixture.rawToken(),booleanValue,REQUESTED_AT);
        }
    }

    private List<SensorReading> readingsFor(Fixture fixture) {
        return readingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(fixture.sensor().getId(),owner.getId());
    }



    private Map<String,Object> sensorRow(Long sensorId) {
        return jdbcTemplate.queryForMap("SELECT * FROM sensors WHERE id=?",sensorId);
    }



    private List<Map<String,Object>> readingRows(Long sensorId) {
        return jdbcTemplate.queryForList("SELECT * FROM sensor_readings WHERE sensor_id=? ORDER BY id",sensorId);
    }



    private List<Map<String,Object>> checkpointRows(Long sensorId) {
        return jdbcTemplate.queryForList("SELECT * FROM sensor_rollup_checkpoints WHERE sensor_id=? ORDER BY stage",sensorId);
    }



    private Instant coveredUntil(Long sensorId,RollupStage stage) {
        return jdbcTemplate.queryForObject("SELECT covered_until FROM sensor_rollup_checkpoints WHERE sensor_id=? AND stage=?",Timestamp.class,sensorId,stage.name()).toInstant();
    }



    private static void assertInvalidated(SensorRollupCheckpoint checkpoint,Instant coverageStart,Instant coveredUntil) {
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(coverageStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(coveredUntil);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
        assertThat(checkpoint.getCreatedAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(TEST_INSTANT);
    }



    private static void assertCheckpointUnchanged(SensorRollupCheckpoint actual,SensorRollupCheckpoint expected) {
        assertThat(actual).usingRecursiveComparison().ignoringFields("sensor").isEqualTo(expected);
    }



    private record Fixture(Sensor sensor,String rawToken) {
        @Override
        public String toString() {
            return "Fixture[sensorId=" + sensor.getId() + ", rawToken=[REDACTED]]";
        }
    }
}