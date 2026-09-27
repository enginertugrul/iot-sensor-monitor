package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.RollupCandidateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupBucketResult.Status.ADVANCED;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupBucketResult.Status.UP_TO_DATE;
import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class HourlySensorRollupBucketProcessorIT {

    private static final Instant START = TEST_INSTANT.minusSeconds(7200);
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant SENSOR_CREATED_AT = START.minusSeconds(172800);
    private static final Instant BEFORE_ROLLUP = TEST_INSTANT.minusSeconds(60);

    @Autowired
    private HourlySensorRollupBucketProcessor processor;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private HourlySensorSummaryRepository summaryRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private SensorRollupCheckpointRepository checkpointRepository;

    private AppUser owner;


    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        owner = appUserRepository.saveAndFlush(new AppUser("hourly-rollup-" + UUID.randomUUID() + "@example.com","test-password-hash",SENSOR_CREATED_AT));
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
            "TEMPERATURE,C",
            "HUMIDITY,PERCENT"
    })
    void initializesFromTheUtcHourOfTheFirstReadingAndCommitsNumericSummary(SensorType type,MeasurementUnit unit) {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(type,firstReadingAt);
        readingRepository.saveAllAndFlush(List.of(
                numericReading(sensor,20.25,firstReadingAt),
                numericReading(sensor,30.5,END.minusNanos(1000)),
                numericReading(sensor,90.0,END)));

        HourlyRollupBucketResult result = processor.advanceNextClosedHour(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,2));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        HourlySensorSummary summary = summaryFor(sensor,START);
        assertThat(summary.getId()).isPositive();
        assertThat(summary.getBucketStart()).isEqualTo(START);
        assertThat(summary.getBucketEnd()).isEqualTo(END);
        assertThat(summary.getFinalizedAt()).isEqualTo(TEST_INSTANT);
        assertThat(summary.getRefreshedAt()).isEqualTo(TEST_INSTANT);
        assertNumericSummary(summary,unit,2,"50.75",20.25,30.5);

        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
        assertThat(checkpointFor(sensor).getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(summaryRows(sensor)).hasSize(1);
        assertThat(checkpointRows(sensor)).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM sensor_readings WHERE sensor_id=?",Long.class,sensor.getId()))
                .isEqualTo(3L);
    }



    @Test
    void usesCurrentCheckpointWithAStaleSnapshotAndProcessesOnlyOneHalfOpenHourPerCall() {
        Instant firstReadingAt = START.minusSeconds(1800);
        Instant coverageStart = START.minusSeconds(3600);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);
        readingRepository.saveAllAndFlush(List.of(
                SensorReading.temperature(sensor,99.0,firstReadingAt),
                SensorReading.temperature(sensor,20.0,START),
                SensorReading.temperature(sensor,30.0,END.minusNanos(1000)),
                SensorReading.temperature(sensor,40.0,END)));
        RollupCandidateProjection snapshot = candidateFor(sensor);

        HourlyRollupBucketResult first = processor.advanceNextClosedHour(snapshot,TEST_INSTANT);
        HourlyRollupBucketResult second = processor.advanceNextClosedHour(snapshot,TEST_INSTANT);

        assertThat(first).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),coverageStart,START,coverageStart,START,1));
        assertThat(second).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,coverageStart,END,2));

        assertNumericSummary(summaryFor(sensor,coverageStart),MeasurementUnit.C,1,"99",99.0,99.0);
        assertNumericSummary(summaryFor(sensor,START),MeasurementUnit.C,2,"50",20.0,30.0);
        assertThat(summaryRepository.findBySensorIdAndBucketStart(sensor.getId(),END)).isEmpty();
        assertThat(summaryRows(sensor)).hasSize(2);
        assertAdvancedCheckpoint(sensor,coverageStart,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void summarizesMotionReadingsWithoutNumericValuesOrAUnit() {
        Sensor sensor = persistSensor(SensorType.MOTION,START);
        readingRepository.saveAllAndFlush(List.of(
                SensorReading.motion(sensor,true,START),
                SensorReading.motion(sensor,false,START.plusSeconds(1800)),
                SensorReading.motion(sensor,true,END.minusNanos(1000)),
                SensorReading.motion(sensor,true,END)));

        HourlyRollupBucketResult result = processor.advanceNextClosedHour(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,3));
        assertMotionSummary(summaryFor(sensor,START),3,2);
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void persistsAnEmptySummaryAndAdvancesAcrossAnHourWithoutReadings(SensorType type) {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(type,firstReadingAt);
        SensorReading firstReading = switch (type) {
            case TEMPERATURE -> SensorReading.temperature(sensor,20.0,firstReadingAt);
            case HUMIDITY -> SensorReading.humidity(sensor,50.0,firstReadingAt);
            case MOTION -> SensorReading.motion(sensor,true,firstReadingAt);
        };
        readingRepository.saveAndFlush(firstReading);
        RollupCandidateProjection snapshot = candidateFor(sensor);

        HourlyRollupBucketResult first = processor.advanceNextClosedHour(snapshot,TEST_INSTANT);
        HourlyRollupBucketResult empty = processor.advanceNextClosedHour(snapshot,TEST_INSTANT);

        assertThat(first).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,1));
        assertThat(empty).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),END,TEST_INSTANT,START,TEST_INSTANT,0));

        HourlySensorSummary summary = summaryFor(sensor,END);
        assertThat(summary.getBucketStart()).isEqualTo(END);
        assertThat(summary.getBucketEnd()).isEqualTo(TEST_INSTANT);

        if (type == SensorType.MOTION) {
            assertMotionSummary(summary,0,0);
        } else {
            MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
            assertNumericSummary(summary,unit,0,null,null,null);
        }

        assertThat(summaryRows(sensor)).hasSize(2);
        assertAdvancedCheckpoint(sensor,START,END,TEST_INSTANT,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @ValueSource(longs = {-3600,0})
    void initializesCheckpointWithoutSummarizingAnIneligibleFirstHour(long cutoffOffset) {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);
        readingRepository.saveAndFlush(SensorReading.temperature(sensor,20.0,firstReadingAt));

        HourlyRollupBucketResult result = processor.advanceNextClosedHour(candidateFor(sensor),START.plusSeconds(cutoffOffset));

        assertThat(result).isEqualTo(new HourlyRollupBucketResult(UP_TO_DATE,sensor.getId(),null,null,START,START,0));
        assertThat(summaryRows(sensor)).isEmpty();

        SensorRollupCheckpoint checkpoint = checkpointFor(sensor);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
    }



    @ParameterizedTest
    @ValueSource(longs = {-3600,0})
    void leavesPersistedStateUnchangedWhenCoverageAlreadyReachesOrExceedsCutoff(long cutoffOffset) {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);
        readingRepository.saveAndFlush(SensorReading.temperature(sensor,20.0,firstReadingAt));
        RollupCandidateProjection snapshot = candidateFor(sensor);
        processor.advanceNextClosedHour(snapshot,END);

        List<Map<String,Object>> summariesBefore = summaryRows(sensor);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);

        HourlyRollupBucketResult result = processor.advanceNextClosedHour(snapshot,END.plusSeconds(cutoffOffset));

        assertThat(result).isEqualTo(new HourlyRollupBucketResult(UP_TO_DATE,sensor.getId(),null,null,START,END,0));
        assertThat(summaryRows(sensor)).containsExactlyElementsOf(summariesBefore);
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void replacesExistingAggregateWithoutChangingIdentityOrMovingTimestampsBackwards(boolean hasReadings) {
        Instant firstReadingAt = START.plusSeconds(900);
        Instant attemptedAt = TEST_INSTANT.plusSeconds(60);
        Instant refreshedAt = TEST_INSTANT.plusSeconds(120);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);

        if (hasReadings) {
            readingRepository.saveAllAndFlush(List.of(
                    SensorReading.temperature(sensor,20.0,firstReadingAt),
                    SensorReading.temperature(sensor,30.0,END.minusNanos(1000))));
        }

        SensorSummaryAggregate staleAggregate = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("99"),99.0,99.0);
        HourlySensorSummary original = HourlySensorSummary.create(sensor,START,staleAggregate,BEFORE_ROLLUP);
        original.refresh(staleAggregate,refreshedAt);
        original = summaryRepository.saveAndFlush(original);
        checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(sensor,RAW_TO_HOURLY,START,attemptedAt));

        HourlyRollupBucketResult result = processor.advanceNextClosedHour(candidateFor(sensor),END);

        long samples = hasReadings ? 2 : 0;
        assertThat(result).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,samples));

        HourlySensorSummary refreshed = summaryFor(sensor,START);
        assertThat(refreshed.getId()).isEqualTo(original.getId());
        assertThat(refreshed.getBucketStart()).isEqualTo(START);
        assertThat(refreshed.getBucketEnd()).isEqualTo(END);
        assertThat(refreshed.getFinalizedAt()).isEqualTo(BEFORE_ROLLUP);
        assertThat(refreshed.getRefreshedAt()).isEqualTo(refreshedAt);

        if (hasReadings) {
            assertNumericSummary(refreshed,MeasurementUnit.C,2,"50",20.0,30.0);
        } else {
            assertNumericSummary(refreshed,MeasurementUnit.C,0,null,null,null);
        }

        assertThat(summaryRows(sensor)).hasSize(1);
        assertAdvancedCheckpoint(sensor,START,START,END,attemptedAt,refreshedAt);
        assertThat(checkpointFor(sensor).getCreatedAt()).isEqualTo(attemptedAt);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rollsBackFlushedSummaryAndCheckpointChangesAndAllowsRetry(boolean existingState) {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);
        readingRepository.saveAndFlush(SensorReading.temperature(sensor,20.0,firstReadingAt));

        if (existingState) {
            SensorSummaryAggregate stale = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("99"),99.0,99.0);
            summaryRepository.saveAndFlush(HourlySensorSummary.create(sensor,START,stale,BEFORE_ROLLUP));
            checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(sensor,RAW_TO_HOURLY,START,BEFORE_ROLLUP));
        }

        RollupCandidateProjection snapshot = candidateFor(sensor);
        List<Map<String,Object>> summariesBefore = summaryRows(sensor);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);
        IllegalStateException failure = new IllegalStateException("Fail after flushing hourly rollup changes");

        doAnswer(invocation -> {
            SensorRollupCheckpoint checkpoint = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(entityManager.contains(checkpoint)).isTrue();

            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject("SELECT source_sample_count FROM hourly_sensor_summaries WHERE sensor_id=?",Long.class,sensor.getId()))
                    .isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject("SELECT numeric_sum FROM hourly_sensor_summaries WHERE sensor_id=?",BigDecimal.class,sensor.getId()))
                    .isEqualByComparingTo("20");
            assertThat(persistedCoveredUntil(sensor)).isEqualTo(END);
            throw failure;
        }).when(checkpointRepository).saveAndFlush(argThat((SensorRollupCheckpoint checkpoint) ->
                checkpoint != null
                        && sensor.getId().equals(checkpoint.getSensor().getId())
                        && END.equals(checkpoint.getCoveredUntil())));

        assertThatThrownBy(() -> processor.advanceNextClosedHour(snapshot,END))
                .isExactlyInstanceOf(InvalidDataAccessApiUsageException.class).cause().isSameAs(failure);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(summaryRows(sensor)).containsExactlyElementsOf(summariesBefore);
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);

        reset(checkpointRepository);

        HourlyRollupBucketResult retried = processor.advanceNextClosedHour(snapshot,END);

        assertThat(retried).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,1));
        assertThat(summaryRows(sensor)).hasSize(1);
        assertThat(checkpointRows(sensor)).hasSize(1);
        assertNumericSummary(summaryFor(sensor,START),MeasurementUnit.C,1,"20",20.0,20.0);
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void commitsItsOwnTransactionEvenWhenTheCallingTransactionRollsBack() {
        Instant firstReadingAt = START.plusSeconds(900);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,firstReadingAt);
        readingRepository.saveAndFlush(SensorReading.temperature(sensor,20.0,firstReadingAt));
        RollupCandidateProjection snapshot = candidateFor(sensor);
        TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

        callerTransaction.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            HourlyRollupBucketResult result = processor.advanceNextClosedHour(snapshot,END);

            assertThat(result).isEqualTo(new HourlyRollupBucketResult(ADVANCED,sensor.getId(),START,END,START,END,1));
            status.setRollbackOnly();
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(summaryRows(sensor)).hasSize(1);
        assertNumericSummary(summaryFor(sensor,START),MeasurementUnit.C,1,"20",20.0,20.0);
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void rejectsASnapshotWhoseSensorWasDeletedBeforeProcessing() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,START.plusSeconds(900));
        RollupCandidateProjection snapshot = candidateFor(sensor);
        jdbcTemplate.update("DELETE FROM sensors WHERE id=?",sensor.getId());

        assertThatThrownBy(() -> processor.advanceNextClosedHour(snapshot,END))
                .isExactlyInstanceOf(SensorNotFoundException.class);

        assertThat(summaryRows(sensor)).isEmpty();
        assertThat(checkpointRows(sensor)).isEmpty();
    }



    @Test
    void rejectsMissingSnapshotAndInvalidCutoffsWithoutPersistingRollupState() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,START.plusSeconds(900));
        RollupCandidateProjection snapshot = candidateFor(sensor);

        assertThatThrownBy(() -> processor.advanceNextClosedHour(null,END))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("sensor must not be null");

        assertThatThrownBy(() -> processor.advanceNextClosedHour(snapshot,null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("eligibleCoveredUntil must not be null");

        assertThatThrownBy(() -> processor.advanceNextClosedHour(snapshot,END.plusNanos(1)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("eligibleCoveredUntil must be aligned to a UTC hour");

        assertThat(summaryRows(sensor)).isEmpty();
        assertThat(checkpointRows(sensor)).isEmpty();
    }



    private Sensor persistSensor(SensorType type,Instant firstReadingAt) {
        Sensor sensor = new Sensor(owner,type,"Hourly " + UUID.randomUUID(),"Istanbul","Kadikoy","Window","Asia/Kathmandu",SENSOR_CREATED_AT);
        sensor.recordFirstReading(firstReadingAt,BEFORE_ROLLUP);
        return sensorRepository.saveAndFlush(sensor);
    }



    private static SensorReading numericReading(Sensor sensor,double value,Instant recordedAt) {
        return switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,value,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,value,recordedAt);
            case MOTION -> throw new IllegalArgumentException("Numeric reading requires a numeric sensor");
        };
    }



    private RollupCandidateProjection candidateFor(Sensor sensor) {
        return sensorRepository.findSensorsForRollup().stream()
                .filter(candidate -> candidate.getId().equals(sensor.getId()))
                .findFirst()
                .orElseThrow();
    }



    private HourlySensorSummary summaryFor(Sensor sensor,Instant bucketStart) {
        return summaryRepository.findBySensorIdAndBucketStart(sensor.getId(),bucketStart).orElseThrow();
    }



    private SensorRollupCheckpoint checkpointFor(Sensor sensor) {
        return checkpointRepository.findBySensorIdAndStage(sensor.getId(),RAW_TO_HOURLY).orElseThrow();
    }



    private List<Map<String,Object>> summaryRows(Sensor sensor) {
        return jdbcTemplate.queryForList("SELECT * FROM hourly_sensor_summaries WHERE sensor_id=? ORDER BY bucket_start",sensor.getId());
    }



    private List<Map<String,Object>> checkpointRows(Sensor sensor) {
        return jdbcTemplate.queryForList("SELECT * FROM sensor_rollup_checkpoints WHERE sensor_id=? ORDER BY stage",sensor.getId());
    }



    private Instant persistedCoveredUntil(Sensor sensor) {
        return jdbcTemplate.queryForObject(
                "SELECT covered_until FROM sensor_rollup_checkpoints WHERE sensor_id=? AND stage=?",
                Timestamp.class,sensor.getId(),RAW_TO_HOURLY.name()).toInstant();
    }



    private static void assertNumericSummary(HourlySensorSummary summary,MeasurementUnit unit,long samples,String sum,Double minimum,Double maximum) {
        assertThat(summary.getSourceSampleCount()).isEqualTo(samples);
        assertThat(summary.getUnit()).isEqualTo(unit);
        assertThat(summary.getNumericMinimum()).isEqualTo(minimum);
        assertThat(summary.getNumericMaximum()).isEqualTo(maximum);
        assertThat(summary.getTrueSampleCount()).isNull();

        if (sum == null) {
            assertThat(summary.getNumericSum()).isNull();
        } else {
            assertThat(summary.getNumericSum()).isEqualByComparingTo(sum);
        }
    }



    private static void assertMotionSummary(HourlySensorSummary summary,long samples,long trueSamples) {
        assertThat(summary.getSourceSampleCount()).isEqualTo(samples);
        assertThat(summary.getTrueSampleCount()).isEqualTo(trueSamples);
        assertThat(summary.getUnit()).isNull();
        assertThat(summary.getNumericSum()).isNull();
        assertThat(summary.getNumericMinimum()).isNull();
        assertThat(summary.getNumericMaximum()).isNull();
    }



    private void assertAdvancedCheckpoint(Sensor sensor,Instant coverageStart,Instant bucketStart,Instant bucketEnd,Instant attemptedAt,Instant completedAt) {
        SensorRollupCheckpoint checkpoint = checkpointFor(sensor);
        assertThat(checkpoint.getStage()).isEqualTo(RAW_TO_HOURLY);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(coverageStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(bucketEnd);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(bucketStart);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isEqualTo(bucketStart);
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isEqualTo(bucketEnd);
        assertThat(checkpoint.getLastSuccessfulAt()).isEqualTo(completedAt);
        assertThat(checkpoint.getLastAdvancedAt()).isEqualTo(completedAt);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(completedAt);
    }
}