package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.HOURLY_TO_DAILY;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupBucketResult.Status.ADVANCED;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupBucketResult.Status.UP_TO_DATE;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupBucketResult.Status.WAITING_FOR_HOURLY;
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
class DailySensorRollupBucketProcessorIT {

    private static final LocalDate DATE = LocalDate.of(2026,1,13);
    private static final Instant START = Instant.parse("2026-01-13T00:00:00Z");
    private static final Instant END = Instant.parse("2026-01-14T00:00:00Z");
    private static final Instant BEFORE_ROLLUP = TEST_INSTANT.minusSeconds(60);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private DailySensorRollupBucketProcessor processor;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private DailySensorSummaryRepository dailyRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean
    private SensorRollupCheckpointRepository checkpointRepository;

    private TransactionTemplate transactions;
    private AppUser owner;


    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        transactions = new TransactionTemplate(transactionManager);
        owner = appUserRepository.saveAndFlush(new AppUser(
                "daily-rollup-" + UUID.randomUUID() + "@example.com","test-password-hash",Instant.parse("2025-01-01T00:00:00Z")));
    }


    @AfterEach
    void tearDown() {
        reset(checkpointRepository);
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",owner.getId());
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",owner.getId());
        }
    }



    @Test
    void waitsWithoutCreatingDailyStateWhenTheHourlyCheckpointIsMissing() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START.plusSeconds(900));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                WAITING_FOR_HOURLY,sensor.getId(),null,"UTC",null,null,null,null,null,null,0,0,0));
        assertThat(dailyRows(sensor)).isEmpty();
        assertThat(checkpointRows(sensor)).isEmpty();
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-13T00:00:00Z,2026-01-14T00:00:00Z,2026-01-13T23:00:00Z,2026-01-14T00:00:00Z,24",
            "Asia/Kolkata,2026-01-12T18:30:00Z,2026-01-13T18:30:00Z,2026-01-13T18:00:00Z,2026-01-13T19:00:00Z,23",
            "Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z,2026-01-13T18:00:00Z,2026-01-13T19:00:00Z,23"
    })
    void waitsUntilHourlyCoverageReachesTheRequiredUtcBoundary(String zoneId,String startText,String endText,String incompleteText,String requiredText,int hourlyRows) {

        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        Instant incomplete = Instant.parse(incompleteText);
        Instant required = Instant.parse(requiredText);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,zoneId,start.plusSeconds(900));
        seedHourlyThrough(sensor,incomplete);
        RollupCandidateProjection snapshot = candidateFor(sensor);

        DailyRollupBucketResult waiting = processor.advanceNextClosedDay(snapshot,end);

        assertThat(waiting).isEqualTo(new DailyRollupBucketResult(
                WAITING_FOR_HOURLY,sensor.getId(),DATE,zoneId,start,end,start,start,required,incomplete,0,0,0));
        assertThat(dailyRows(sensor)).isEmpty();

        SensorRollupCheckpoint checkpoint = checkpointFor(sensor,HOURLY_TO_DAILY);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(start);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(start);
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpointFor(sensor,RAW_TO_HOURLY).getCoveredUntil()).isEqualTo(incomplete);

        seedHourlyThrough(sensor,required);

        DailyRollupBucketResult advanced = processor.advanceNextClosedDay(snapshot,end);

        assertThat(advanced).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,zoneId,start,end,start,end,required,required,0,hourlyRows,0));
        assertAggregate(summaryFor(sensor,start),empty(SensorType.TEMPERATURE));
        assertAdvancedCheckpoint(sensor,start,start,end,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void initializesCoverageWithoutAttemptingADayThatHasNotClosed() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START.plusSeconds(900));
        seedHourlyThrough(sensor,END);
        RollupCandidateProjection snapshot = candidateFor(sensor);

        DailyRollupBucketResult result = processor.advanceNextClosedDay(snapshot,END.minusNanos(1000));

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                UP_TO_DATE,sensor.getId(),DATE,"UTC",START,END,START,START,END,END,0,0,0));
        assertThat(dailyRows(sensor)).isEmpty();

        SensorRollupCheckpoint checkpoint = checkpointFor(sensor,HOURLY_TO_DAILY);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();

        List<Map<String,Object>> before = checkpointRows(sensor);

        processor.advanceNextClosedDay(snapshot,START);

        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(before);
        assertThat(dailyRows(sensor)).isEmpty();
    }



    @ParameterizedTest
    @ValueSource(longs = {-3600,3600})
    void rejectsHourlyCoverageThatDoesNotBeginAtTheFirstRecordedUtcHour(long offset) {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START.plusSeconds(900));
        checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(
                sensor,RAW_TO_HOURLY,START.plusSeconds(offset),BEFORE_ROLLUP));
        List<Map<String,Object>> before = checkpointRows(sensor);
        RollupCandidateProjection snapshot = candidateFor(sensor);

        assertThatThrownBy(() -> processor.advanceNextClosedDay(snapshot,END))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Hourly coverage checkpoint does not begin at the sensor's first recorded UTC hour");

        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(before);
        assertThat(dailyRows(sensor)).isEmpty();
    }



    @Test
    void rejectsAMissingHourlySummaryRollsBackInitializationAndAllowsRetryAfterRepair() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START);
        readingRepository.saveAndFlush(SensorReading.temperature(sensor,20.0,START));
        seedHourlyThrough(sensor,END);
        replaceHourlyAggregate(sensor,START,aggregate(sensor.getType(),1,"20",20.0,20.0,0));

        Instant missingHour = START.plusSeconds(3600);
        jdbcTemplate.update("DELETE FROM hourly_sensor_summaries WHERE sensor_id=? AND bucket_start=?",
                sensor.getId(),Timestamp.from(missingHour));

        RollupCandidateProjection snapshot = candidateFor(sensor);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);

        assertThatThrownBy(() -> processor.advanceNextClosedDay(snapshot,END))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Hourly coverage contains 24 buckets but 23 summary rows were found for sensor " + sensor.getId());

        assertThat(dailyRows(sensor)).isEmpty();
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);

        hourlyRepository.saveAndFlush(HourlySensorSummary.create(sensor,missingHour,empty(sensor.getType()),BEFORE_ROLLUP));

        DailyRollupBucketResult retried = processor.advanceNextClosedDay(snapshot,END);

        assertThat(retried).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,1,24,0));
        assertAggregate(summaryFor(sensor,START),aggregate(sensor.getType(),1,"20",20.0,20.0,0));
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-13,2026-01-13T00:00:00Z,2026-01-14T00:00:00Z,24",
            "Europe/Istanbul,2026-01-13,2026-01-12T21:00:00Z,2026-01-13T21:00:00Z,24",
            "Europe/Berlin,2025-03-30,2025-03-29T23:00:00Z,2025-03-30T22:00:00Z,23",
            "Europe/Berlin,2025-10-26,2025-10-25T22:00:00Z,2025-10-26T23:00:00Z,25"
    })
    void aggregatesExactlyTheHoursBelongingToRegularAndDstLocalDays(
            String zoneId,String dateText,String startText,String endText,int hours) {
        LocalDate date = LocalDate.parse(dateText);
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        Instant hourlyEnd = end.plusSeconds(3600);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,zoneId,start);
        seedHourlyThrough(sensor,hourlyEnd);

        replaceHourlyAggregate(sensor,start,aggregate(sensor.getType(),2,"30",10.0,20.0,0));
        replaceHourlyAggregate(sensor,end.minusSeconds(3600),aggregate(sensor.getType(),3,"90",20.0,40.0,0));
        replaceHourlyAggregate(sensor,end,aggregate(sensor.getType(),1,"99",99.0,99.0,0));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),end);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),date,zoneId,start,end,start,end,end,hourlyEnd,5,hours,0));

        DailySensorSummary summary = summaryFor(sensor,start);
        assertThat(summary.getLocalDate()).isEqualTo(date);
        assertThat(summary.getTimeZoneId()).isEqualTo(zoneId);
        assertThat(summary.getBucketStart()).isEqualTo(start);
        assertThat(summary.getBucketEnd()).isEqualTo(end);
        assertThat(Duration.between(summary.getBucketStart(),summary.getBucketEnd())).isEqualTo(Duration.ofHours(hours));
        assertAggregate(summary,aggregate(sensor.getType(),5,"120",10.0,40.0,0));
        assertThat(dailyRows(sensor)).hasSize(1);
        assertAdvancedCheckpoint(sensor,start,start,end,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,Asia/Kolkata,2026-01-12T18:30:00Z,2026-01-13T18:30:00Z",
            "TEMPERATURE,Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z",
            "TEMPERATURE,America/St_Johns,2026-01-13T03:30:00Z,2026-01-14T03:30:00Z",
            "HUMIDITY,Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z",
            "MOTION,Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z"
    })
    void combinesFullHoursAndRawBoundaryFragmentsWithoutOverlap(
            SensorType type,String zoneId,String startText,String endText) {
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        Instant firstFullHour = start.truncatedTo(ChronoUnit.HOURS).plusSeconds(3600);
        Instant fullHoursEnd = end.truncatedTo(ChronoUnit.HOURS);
        Instant hourlyEnd = fullHoursEnd.plusSeconds(3600);
        Sensor sensor = persistSensor(type,zoneId,start);

        readingRepository.saveAllAndFlush(List.of(
                reading(sensor,10.0,true,start),
                reading(sensor,20.0,false,firstFullHour.minusNanos(1000)),
                reading(sensor,30.0,true,firstFullHour),
                reading(sensor,40.0,false,fullHoursEnd.minusNanos(1000)),
                reading(sensor,50.0,false,fullHoursEnd),
                reading(sensor,60.0,true,end.minusNanos(1000)),
                reading(sensor,90.0,true,end)));

        seedHourlyThrough(sensor,hourlyEnd);
        replaceHourlyAggregate(sensor,start.truncatedTo(ChronoUnit.HOURS),aggregate(type,2,"30",10.0,20.0,1));
        replaceHourlyAggregate(sensor,firstFullHour,aggregate(type,1,"30",30.0,30.0,1));
        replaceHourlyAggregate(sensor,fullHoursEnd.minusSeconds(3600),aggregate(type,1,"40",40.0,40.0,0));
        replaceHourlyAggregate(sensor,fullHoursEnd,aggregate(type,3,"200",50.0,90.0,2));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),end);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,zoneId,start,end,start,end,hourlyEnd,hourlyEnd,6,23,4));

        DailySensorSummary summary = summaryFor(sensor,start);
        assertThat(summary.getLocalDate()).isEqualTo(DATE);
        assertThat(summary.getTimeZoneId()).isEqualTo(zoneId);
        assertThat(summary.getBucketStart()).isEqualTo(start);
        assertThat(summary.getBucketEnd()).isEqualTo(end);
        assertAggregate(summary,aggregate(type,6,"210",10.0,60.0,3));
        assertAdvancedCheckpoint(sensor,start,start,end,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void usesRawReadingsWhenTheFirstReadingLeavesNoCompleteHourlySourceBucket() {
        String zoneId = "Asia/Kathmandu";
        Instant start = Instant.parse("2026-01-12T18:15:00Z");
        Instant end = Instant.parse("2026-01-13T18:15:00Z");
        Instant firstReadingAt = end.minusSeconds(600);
        Instant hourlyEnd = Instant.parse("2026-01-13T19:00:00Z");
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,zoneId,firstReadingAt);

        readingRepository.saveAllAndFlush(List.of(
                SensorReading.temperature(sensor,20.0,firstReadingAt),
                SensorReading.temperature(sensor,30.0,end.minusNanos(1000)),
                SensorReading.temperature(sensor,99.0,end)));

        seedHourlyThrough(sensor,hourlyEnd);
        replaceHourlyAggregate(sensor,end.truncatedTo(ChronoUnit.HOURS),aggregate(sensor.getType(),3,"149",20.0,99.0,0));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),end);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,zoneId,start,end,start,end,hourlyEnd,hourlyEnd,2,0,2));
        assertAggregate(summaryFor(sensor,start),aggregate(sensor.getType(),2,"50",20.0,30.0,0));
        assertAdvancedCheckpoint(sensor,start,start,end,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void StartsHourlySourceAtTheFirstReadingHourInsteadOfRequiringEarlierSummaryRows() {
        Instant firstHour = START.plusSeconds(12 * 3600);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",firstHour.plusSeconds(900));
        seedHourlyThrough(sensor,END);
        replaceHourlyAggregate(sensor,firstHour,aggregate(sensor.getType(),2,"50",20.0,30.0,0));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,2,12,0));
        assertAggregate(summaryFor(sensor,START),aggregate(sensor.getType(),2,"50",20.0,30.0,0));
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void persistsAnEmptyDailySummaryAndAdvancesCoverage(SensorType type) {
        Sensor sensor = persistSensor(type,"UTC",START);
        seedHourlyThrough(sensor,END);

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,0,24,0));
        assertAggregate(summaryFor(sensor,START),empty(type));
        assertThat(dailyRows(sensor)).hasSize(1);
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void refreshesExistingSummaryWithoutChangingItsIdentityOrMovingTimestampsBackwards(boolean hasSamples) {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START);
        seedHourlyThrough(sensor,END);
        SensorSummaryAggregate expected = hasSamples
                ? aggregate(sensor.getType(),2,"50",20.0,30.0,0)
                : empty(sensor.getType());
        replaceHourlyAggregate(sensor,START,expected);

        Instant attemptedAt = TEST_INSTANT.plusSeconds(60);
        Instant completedAt = TEST_INSTANT.plusSeconds(120);
        SensorSummaryAggregate stale = aggregate(sensor.getType(),1,"99",99.0,99.0,0);
        DailySensorSummary original = DailySensorSummary.create(sensor,DATE,UTC,stale,BEFORE_ROLLUP);
        original.refresh(stale,completedAt);
        original = dailyRepository.saveAndFlush(original);
        checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(sensor,HOURLY_TO_DAILY,START,attemptedAt));

        DailyRollupBucketResult result = processor.advanceNextClosedDay(candidateFor(sensor),END);

        assertThat(result).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,hasSamples ? 2 : 0,24,0));

        DailySensorSummary refreshed = summaryFor(sensor,START);
        assertThat(refreshed.getId()).isEqualTo(original.getId());
        assertThat(refreshed.getFinalizedAt()).isEqualTo(BEFORE_ROLLUP);
        assertThat(refreshed.getRefreshedAt()).isEqualTo(completedAt);
        assertAggregate(refreshed,expected);
        assertThat(dailyRows(sensor)).hasSize(1);
        assertThat(checkpointFor(sensor,HOURLY_TO_DAILY).getCreatedAt()).isEqualTo(attemptedAt);
        assertAdvancedCheckpoint(sensor,START,START,END,attemptedAt,completedAt);
    }



    @Test
    void advancesOneDayPerCallAndStopsAtTheCutoffUsingThePersistedCheckpoint() {
        Instant cutoff = END.plusSeconds(86400);
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START);
        seedHourlyThrough(sensor,cutoff);
        replaceHourlyAggregate(sensor,START,aggregate(sensor.getType(),1,"20",20.0,20.0,0));
        RollupCandidateProjection snapshot = candidateFor(sensor);

        DailyRollupBucketResult first = processor.advanceNextClosedDay(snapshot,cutoff);

        assertThat(first).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,cutoff,1,24,0));
        assertThat(dailyRows(sensor)).hasSize(1);

        DailyRollupBucketResult second = processor.advanceNextClosedDay(snapshot,cutoff);

        assertThat(second).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE.plusDays(1),"UTC",END,cutoff,START,cutoff,cutoff,cutoff,0,24,0));
        assertThat(dailyRows(sensor)).hasSize(2);
        assertAggregate(summaryFor(sensor,END),empty(sensor.getType()));
        assertAdvancedCheckpoint(sensor,START,END,cutoff,TEST_INSTANT,TEST_INSTANT);

        List<Map<String,Object>> summariesBefore = dailyRows(sensor);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);

        DailyRollupBucketResult upToDate = processor.advanceNextClosedDay(snapshot,cutoff);

        assertThat(upToDate).isEqualTo(new DailyRollupBucketResult(
                UP_TO_DATE,sensor.getId(),DATE.plusDays(2),"UTC",cutoff,cutoff.plusSeconds(86400),
                START,cutoff,cutoff.plusSeconds(86400),cutoff,0,0,0));
        assertThat(dailyRows(sensor)).containsExactlyElementsOf(summariesBefore);
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rollsBackFlushedSummaryAndCheckpointChangesAndAllowsRetry(boolean existingState) {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START);
        seedHourlyThrough(sensor,END);
        replaceHourlyAggregate(sensor,START,aggregate(sensor.getType(),1,"20",20.0,20.0,0));

        if (existingState) {
            SensorSummaryAggregate stale = aggregate(sensor.getType(),1,"99",99.0,99.0,0);
            dailyRepository.saveAndFlush(DailySensorSummary.create(sensor,DATE,UTC,stale,BEFORE_ROLLUP));
            checkpointRepository.saveAndFlush(SensorRollupCheckpoint.initialize(sensor,HOURLY_TO_DAILY,START,BEFORE_ROLLUP));
        }

        RollupCandidateProjection snapshot = candidateFor(sensor);
        List<Map<String,Object>> summariesBefore = dailyRows(sensor);
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);
        RuntimeException failure = new RuntimeException("Forced daily rollup failure after flush");

        doAnswer(invocation -> {
            SensorRollupCheckpoint checkpoint = invocation.getArgument(0);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(entityManager.contains(checkpoint)).isTrue();

            entityManager.flush();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT source_sample_count FROM daily_sensor_summaries WHERE sensor_id=?",Long.class,sensor.getId()))
                    .isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT numeric_sum FROM daily_sensor_summaries WHERE sensor_id=?",BigDecimal.class,sensor.getId()))
                    .isEqualByComparingTo("20");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT covered_until FROM sensor_rollup_checkpoints WHERE sensor_id=? AND stage='HOURLY_TO_DAILY'",
                    Timestamp.class,sensor.getId()).toInstant()).isEqualTo(END);
            throw failure;
        }).when(checkpointRepository).saveAndFlush(argThat((SensorRollupCheckpoint checkpoint) ->
                checkpoint != null
                        && checkpoint.getStage() == HOURLY_TO_DAILY
                        && sensor.getId().equals(checkpoint.getSensor().getId())
                        && END.equals(checkpoint.getCoveredUntil())));

        assertThatThrownBy(() -> processor.advanceNextClosedDay(snapshot,END)).isSameAs(failure);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(dailyRows(sensor)).containsExactlyElementsOf(summariesBefore);
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);

        reset(checkpointRepository);

        DailyRollupBucketResult retried = processor.advanceNextClosedDay(snapshot,END);

        assertThat(retried).isEqualTo(new DailyRollupBucketResult(
                ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,1,24,0));
        assertThat(dailyRows(sensor)).hasSize(1);
        assertAggregate(summaryFor(sensor,START),aggregate(sensor.getType(),1,"20",20.0,20.0,0));
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    @Test
    void commitsItsOwnTransactionEvenWhenTheCallingTransactionRollsBack() {
        Sensor sensor = persistSensor(SensorType.TEMPERATURE,"UTC",START);
        seedHourlyThrough(sensor,END);
        replaceHourlyAggregate(sensor,START,aggregate(sensor.getType(),1,"20",20.0,20.0,0));
        RollupCandidateProjection snapshot = candidateFor(sensor);

        transactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            DailyRollupBucketResult result = processor.advanceNextClosedDay(snapshot,END);

            assertThat(result).isEqualTo(new DailyRollupBucketResult(
                    ADVANCED,sensor.getId(),DATE,"UTC",START,END,START,END,END,END,1,24,0));
            status.setRollbackOnly();
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(dailyRows(sensor)).hasSize(1);
        assertAggregate(summaryFor(sensor,START),aggregate(sensor.getType(),1,"20",20.0,20.0,0));
        assertAdvancedCheckpoint(sensor,START,START,END,TEST_INSTANT,TEST_INSTANT);
    }



    private Sensor persistSensor(SensorType type,String zoneId,Instant firstReadingAt) {
        Sensor sensor = new Sensor(
                owner,type,"Daily " + UUID.randomUUID(),"Istanbul","Kadikoy","Window",zoneId,firstReadingAt.minusSeconds(172800));
        sensor.recordFirstReading(firstReadingAt,BEFORE_ROLLUP);
        return sensorRepository.saveAndFlush(sensor);
    }



    private void seedHourlyThrough(Sensor sensor,Instant coveredUntil) {
        transactions.executeWithoutResult(status -> {
            SensorRollupCheckpoint checkpoint = checkpointRepository.findBySensorIdAndStage(sensor.getId(),RAW_TO_HOURLY)
                    .orElseGet(() -> SensorRollupCheckpoint.initialize(
                            sensor,RAW_TO_HOURLY,sensor.getFirstReadingAt().truncatedTo(ChronoUnit.HOURS),BEFORE_ROLLUP));
            List<HourlySensorSummary> summaries = new ArrayList<>();

            for (Instant hour = checkpoint.getCoveredUntil(); hour.isBefore(coveredUntil); hour = hour.plusSeconds(3600)) {
                summaries.add(HourlySensorSummary.create(sensor,hour,empty(sensor.getType()),BEFORE_ROLLUP));
                checkpoint.recordAttempt(hour,BEFORE_ROLLUP);
                checkpoint.advanceContiguously(hour,hour.plusSeconds(3600),BEFORE_ROLLUP);
            }

            hourlyRepository.saveAllAndFlush(summaries);
            checkpointRepository.saveAndFlush(checkpoint);
        });
    }



    private void replaceHourlyAggregate(Sensor sensor,Instant bucketStart,SensorSummaryAggregate aggregate) {
        transactions.executeWithoutResult(status -> {
            HourlySensorSummary summary = hourlyRepository.findBySensorIdAndBucketStart(sensor.getId(),bucketStart).orElseThrow();
            summary.refresh(aggregate,BEFORE_ROLLUP);
            hourlyRepository.saveAndFlush(summary);
        });
    }



    private RollupCandidateProjection candidateFor(Sensor sensor) {
        return sensorRepository.findSensorsForRollup().stream()
                .filter(candidate -> candidate.getId().equals(sensor.getId()))
                .findFirst()
                .orElseThrow();
    }



    private DailySensorSummary summaryFor(Sensor sensor,Instant bucketStart) {
        return dailyRepository.findBySensorIdAndBucketStart(sensor.getId(),bucketStart).orElseThrow();
    }



    private SensorRollupCheckpoint checkpointFor(Sensor sensor,RollupStage stage) {
        return checkpointRepository.findBySensorIdAndStage(sensor.getId(),stage).orElseThrow();
    }



    private List<Map<String,Object>> dailyRows(Sensor sensor) {
        return jdbcTemplate.queryForList("SELECT * FROM daily_sensor_summaries WHERE sensor_id=? ORDER BY bucket_start",sensor.getId());
    }



    private List<Map<String,Object>> checkpointRows(Sensor sensor) {
        return jdbcTemplate.queryForList("SELECT * FROM sensor_rollup_checkpoints WHERE sensor_id=? ORDER BY stage",sensor.getId());
    }



    private static SensorReading reading(Sensor sensor,double numericValue,boolean motionDetected,Instant recordedAt) {
        return switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,numericValue,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,numericValue,recordedAt);
            case MOTION -> SensorReading.motion(sensor,motionDetected,recordedAt);
        };
    }



    private static SensorSummaryAggregate empty(SensorType type) {
        return aggregate(type,0,null,null,null,0);
    }



    private static SensorSummaryAggregate aggregate(
            SensorType type,long samples,String sum,Double minimum,Double maximum,long trueSamples) {
        if (type == SensorType.MOTION) {
            return SensorSummaryAggregate.booleanSamples(samples,trueSamples);
        }

        MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
        return SensorSummaryAggregate.numeric(samples,unit,sum == null ? null : new BigDecimal(sum),minimum,maximum);
    }



    private static void assertAggregate(DailySensorSummary summary,SensorSummaryAggregate expected) {
        assertThat(summary.getSourceSampleCount()).isEqualTo(expected.getSourceSampleCount());
        assertThat(summary.getUnit()).isEqualTo(expected.getUnit());
        assertThat(summary.getNumericMinimum()).isEqualTo(expected.getNumericMinimum());
        assertThat(summary.getNumericMaximum()).isEqualTo(expected.getNumericMaximum());
        assertThat(summary.getTrueSampleCount()).isEqualTo(expected.getTrueSampleCount());

        if (expected.getNumericSum() == null) {
            assertThat(summary.getNumericSum()).isNull();
        } else {
            assertThat(summary.getNumericSum()).isEqualByComparingTo(expected.getNumericSum());
        }
    }



    private void assertAdvancedCheckpoint(
            Sensor sensor,Instant coverageStart,Instant bucketStart,Instant bucketEnd,Instant attemptedAt,Instant completedAt) {
        SensorRollupCheckpoint checkpoint = checkpointFor(sensor,HOURLY_TO_DAILY);
        assertThat(checkpoint.getStage()).isEqualTo(HOURLY_TO_DAILY);
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