package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummary;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.GeneratedSensorIngestionToken;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.SensorReadingIngestionService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailySensorRollupService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlySensorRollupService;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeTierResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataRetentionRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataRetentionService;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.HOURLY_TO_DAILY;
import static com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage.RAW_TO_HOURLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "app.sensor-data.lifecycle.raw-retention=P2D",
        "app.sensor-data.lifecycle.hourly-retention=P4D",
        "app.sensor-data.lifecycle.daily-retention=P8D"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class SensorDataLifecycleIT {

    private static final Instant DAY_START = Instant.parse("2026-01-13T00:00:00Z");
    private static final Instant DAY_END = Instant.parse("2026-01-14T00:00:00Z");
    private static final Instant SECOND_DAY_END = Instant.parse("2026-01-15T00:00:00Z");
    private static final Instant CREATED_AT = DAY_START.minus(1,ChronoUnit.DAYS);

    @Autowired
    private SensorReadingIngestionService ingestionService;

    @Autowired
    private HourlySensorRollupService hourlyService;

    @Autowired
    private DailySensorRollupService dailyService;

    @Autowired
    private SensorDataRetentionService retentionService;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private DailySensorSummaryRepository dailyRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private SensorIngestionTokenGenerator tokenGenerator;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean(name = "testClock")
    private Clock clock;

    private AppUser owner;



    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        at(CREATED_AT);
        owner = appUserRepository.saveAndFlush(new AppUser(
                "data-lifecycle-" + UUID.randomUUID() + "@example.com","test-password-hash",CREATED_AT));
    }



    @AfterEach
    void tearDown() {
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",owner.getId());
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",owner.getId());
        }
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void preservesHistoryThroughEachStorageTierUntilThatTierExpires(SensorType type) {
        Fixture fixture = persistSensor(type,"UTC");
        Instant firstHour = DAY_START.plus(22,ChronoUnit.HOURS);
        Instant firstReadingAt = firstHour.plusSeconds(900);
        at(DAY_END.plusSeconds(60));

        ingest(fixture,10.0,true,firstReadingAt);
        ingest(fixture,20.0,false,firstHour.plusSeconds(2700));
        ingest(fixture,30.0,true,DAY_END.minusNanos(1000));
        ingest(fixture,40.0,false,DAY_END);

        assertRowCounts(fixture,4,0,0);
        assertThat(rows("sensor_rollup_checkpoints",fixture)).isEmpty();
        assertFirstReading(fixture,firstReadingAt);

        at(SECOND_DAY_END.plusSeconds(900));
        rollUpHourly(SECOND_DAY_END,26,4);
        rollUpDaily(SECOND_DAY_END,2,4);

        assertRowCounts(fixture,4,26,2);
        assertHourlySampleCount(fixture,4);
        assertAggregate(hourlyFor(fixture,firstHour),type,2,"30",10.0,20.0,1);
        assertAggregate(hourlyFor(fixture,firstHour.plusSeconds(3600)),type,1,"30",30.0,30.0,1);
        assertAggregate(hourlyFor(fixture,DAY_END),type,1,"40",40.0,40.0,0);
        assertAggregate(dailyFor(fixture,DAY_START),type,3,"60",10.0,30.0,2);
        assertAggregate(dailyFor(fixture,DAY_END),type,1,"40",40.0,40.0,0);
        assertThat(dailyFor(fixture,DAY_START).getBucketEnd()).isEqualTo(DAY_END);
        assertThat(dailyFor(fixture,DAY_END).getBucketEnd()).isEqualTo(SECOND_DAY_END);
        assertCheckpoint(fixture,RAW_TO_HOURLY,firstHour,SECOND_DAY_END);
        assertCheckpoint(fixture,HOURLY_TO_DAILY,DAY_START,SECOND_DAY_END);
        assertIdempotentRollups(fixture,SECOND_DAY_END,SECOND_DAY_END);

        List<Map<String,Object>> hourlyBeforePurge = rows("hourly_sensor_summaries",fixture);
        List<Map<String,Object>> dailyBeforePurge = rows("daily_sensor_summaries",fixture);
        List<Map<String,Object>> checkpointsBeforePurge = rows("sensor_rollup_checkpoints",fixture);

        SensorDataRetentionRunResult rawPurge = purgeAt(SECOND_DAY_END.plus(2,ChronoUnit.DAYS));

        assertDeleted(rawPurge,4,0,0);
        assertRowCounts(fixture,0,26,2);
        assertThat(rows("hourly_sensor_summaries",fixture)).containsExactlyElementsOf(hourlyBeforePurge);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBeforePurge);
        assertIdempotentRollups(fixture,SECOND_DAY_END,SECOND_DAY_END);

        SensorDataRetentionRunResult hourlyPurge = purgeAt(SECOND_DAY_END.plus(4,ChronoUnit.DAYS));

        assertDeleted(hourlyPurge,0,26,0);
        assertRowCounts(fixture,0,0,2);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBeforePurge);
        assertIdempotentRollups(fixture,SECOND_DAY_END,SECOND_DAY_END);

        Instant finalPurgeAt = SECOND_DAY_END.plus(8,ChronoUnit.DAYS);
        SensorDataRetentionRunResult dailyPurge = purgeAt(finalPurgeAt);

        assertDeleted(dailyPurge,0,0,2);
        assertRowCounts(fixture,0,0,0);
        assertFirstReading(fixture,firstReadingAt);
        assertThat(rows("sensor_rollup_checkpoints",fixture)).containsExactlyElementsOf(checkpointsBeforePurge);
        assertDeleted(purgeAt(finalPurgeAt),0,0,0);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,22",
            "HUMIDITY,22",
            "MOTION,22",
            "TEMPERATURE,21"
    })
    void rebuildsLateHistoryBeforeAllowingExpiredSourcesToBeDeleted(SensorType type,int lateHour) {
        Fixture fixture = persistSensor(type,"UTC");
        Instant originalFirstHour = DAY_START.plus(22,ChronoUnit.HOURS);
        Instant originalFirstReading = originalFirstHour.plusSeconds(900);
        at(DAY_END.plusSeconds(60));

        ingest(fixture,20.0,true,originalFirstReading);
        ingest(fixture,30.0,false,DAY_START.plus(23,ChronoUnit.HOURS).plusSeconds(900));

        at(DAY_END.plusSeconds(900));
        rollUpHourly(DAY_END,2,2);
        rollUpDaily(DAY_END,1,2);

        HourlySensorSummary originalHourly = hourlyFor(fixture,originalFirstHour);
        DailySensorSummary originalDaily = dailyFor(fixture,DAY_START);
        assertAggregate(originalDaily,type,2,"50",20.0,30.0,1);
        List<Map<String,Object>> hourlyBeforeLateReading = rows("hourly_sensor_summaries",fixture);
        List<Map<String,Object>> dailyBeforeLateReading = rows("daily_sensor_summaries",fixture);

        Instant invalidatedHour = DAY_START.plus(lateHour,ChronoUnit.HOURS);
        Instant lateRecordedAt = invalidatedHour.plusSeconds(1800);
        Instant expectedFirstReading = lateHour == 21 ? lateRecordedAt : originalFirstReading;
        at(DAY_END.plusSeconds(1800));

        ingest(fixture,10.0,true,lateRecordedAt);

        assertFirstReading(fixture,expectedFirstReading);
        assertCheckpoint(fixture,RAW_TO_HOURLY,invalidatedHour,invalidatedHour);
        assertCheckpoint(fixture,HOURLY_TO_DAILY,DAY_START,DAY_START);
        assertThat(rows("hourly_sensor_summaries",fixture)).containsExactlyElementsOf(hourlyBeforeLateReading);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBeforeLateReading);

        DailyRollupRunResult waiting = dailyService.rollUpClosedLocalDays(DAY_END);

        assertThat(waiting.status()).isEqualTo(DailyRollupRunResult.Status.WAITING_FOR_HOURLY);
        assertThat(waiting.advancedBuckets()).isZero();
        assertThat(waiting.waitingSensors()).isEqualTo(1);
        assertThat(waiting.failedSensors()).isZero();
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBeforeLateReading);

        Instant purgeTime = DAY_END.plus(4,ChronoUnit.DAYS);
        SensorDataRetentionRunResult blocked = purgeAt(purgeTime);

        assertThat(blocked.status()).isEqualTo(SensorDataRetentionRunResult.Status.WAITING_FOR_COVERAGE);
        assertThat(blocked.totalRowsDeleted()).isZero();
        assertThat(blocked.rawReadings().status()).isEqualTo(SensorDataPurgeTierResult.Status.WAITING_FOR_COVERAGE);
        assertThat(blocked.rawReadings().waitingForHourlyCoverageSensors()).isEqualTo(1);
        assertThat(blocked.rawReadings().waitingForDailyCoverageSensors()).isEqualTo(1);
        assertThat(blocked.hourlySummaries().status()).isEqualTo(SensorDataPurgeTierResult.Status.WAITING_FOR_COVERAGE);
        assertThat(blocked.hourlySummaries().waitingForDailyCoverageSensors()).isEqualTo(1);
        assertRowCounts(fixture,3,2,1);

        int expectedHourlyBuckets = 24 - lateHour;
        rollUpHourly(DAY_END,expectedHourlyBuckets,3);
        rollUpDaily(DAY_END,1,3);

        HourlySensorSummary refreshedHourly = hourlyFor(fixture,originalFirstHour);
        DailySensorSummary refreshedDaily = dailyFor(fixture,DAY_START);
        assertThat(refreshedHourly.getId()).isEqualTo(originalHourly.getId());
        assertThat(refreshedDaily.getId()).isEqualTo(originalDaily.getId());
        assertThat(refreshedDaily.getFinalizedAt()).isEqualTo(originalDaily.getFinalizedAt());
        assertThat(refreshedDaily.getRefreshedAt()).isEqualTo(purgeTime);
        assertAggregate(refreshedDaily,type,3,"60",10.0,30.0,2);

        if (lateHour == 22) {
            assertAggregate(refreshedHourly,type,2,"30",10.0,20.0,2);
        } else {
            assertAggregate(refreshedHourly,type,1,"20",20.0,20.0,1);
            assertAggregate(hourlyFor(fixture,invalidatedHour),type,1,"10",10.0,10.0,1);
        }

        assertRowCounts(fixture,3,expectedHourlyBuckets,1);
        assertHourlySampleCount(fixture,3);
        assertCheckpoint(fixture,RAW_TO_HOURLY,invalidatedHour,DAY_END);
        assertCheckpoint(fixture,HOURLY_TO_DAILY,DAY_START,DAY_END);
        assertIdempotentRollups(fixture,DAY_END,DAY_END);
        List<Map<String,Object>> repairedDailyRows = rows("daily_sensor_summaries",fixture);

        assertDeleted(purgeAt(purgeTime),3,expectedHourlyBuckets,0);

        assertRowCounts(fixture,0,0,1);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(repairedDailyRows);
        assertFirstReading(fixture,expectedFirstReading);
        assertDeleted(purgeAt(purgeTime),0,0,0);
    }




    @Test
    void retainsSharedUtcHourSourcesUntilBothLocalDaysHaveBeenSummarized() {
        Fixture fixture = persistSensor(SensorType.TEMPERATURE,"Asia/Kathmandu");
        Instant localDayStart = Instant.parse("2026-01-12T18:15:00Z");
        Instant localDayEnd = Instant.parse("2026-01-13T18:15:00Z");
        Instant nextLocalDayEnd = Instant.parse("2026-01-14T18:15:00Z");
        Instant sharedHour = Instant.parse("2026-01-13T18:00:00Z");
        Instant firstHourlyCutoff = Instant.parse("2026-01-13T19:00:00Z");
        Instant nextHourlyCutoff = Instant.parse("2026-01-14T19:00:00Z");
        at(firstHourlyCutoff.plusSeconds(900));

        ingest(fixture,10.0,false,localDayEnd.minusNanos(1000));
        ingest(fixture,20.0,false,localDayEnd);
        ingest(fixture,30.0,false,localDayEnd.plusSeconds(300));

        rollUpHourly(firstHourlyCutoff,1,3);
        DailyRollupRunResult firstDay = rollUpDaily(localDayEnd,1,1);

        assertThat(firstDay.hourlySummaryRowsConsumed()).isZero();
        assertThat(firstDay.rawBoundaryRowsSummarized()).isEqualTo(1);
        assertAggregate(hourlyFor(fixture,sharedHour),SensorType.TEMPERATURE,3,"60",10.0,30.0,0);
        DailySensorSummary firstSummary = dailyFor(fixture,localDayStart);
        assertAggregate(firstSummary,SensorType.TEMPERATURE,1,"10",10.0,10.0,0);
        assertThat(firstSummary.getLocalDate()).isEqualTo(LocalDate.of(2026,1,13));
        assertThat(firstSummary.getTimeZoneId()).isEqualTo("Asia/Kathmandu");
        assertThat(firstSummary.getBucketEnd()).isEqualTo(localDayEnd);

        Instant purgeTime = firstHourlyCutoff.plus(2,ChronoUnit.DAYS);
        List<Map<String,Object>> readingsBeforePurge = rows("sensor_readings",fixture);
        SensorDataRetentionRunResult blocked = purgeAt(purgeTime);

        assertThat(blocked.status()).isEqualTo(SensorDataRetentionRunResult.Status.WAITING_FOR_COVERAGE);
        assertThat(blocked.totalRowsDeleted()).isZero();
        assertThat(blocked.rawReadings().waitingForHourlyCoverageSensors()).isZero();
        assertThat(blocked.rawReadings().waitingForDailyCoverageSensors()).isEqualTo(1);
        assertThat(rows("sensor_readings",fixture)).containsExactlyElementsOf(readingsBeforePurge);
        assertRowCounts(fixture,3,1,1);

        rollUpHourly(nextHourlyCutoff,24,0);
        DailyRollupRunResult secondDay = rollUpDaily(nextLocalDayEnd,1,2);

        assertThat(secondDay.hourlySummaryRowsConsumed()).isEqualTo(23);
        assertThat(secondDay.rawBoundaryRowsSummarized()).isEqualTo(2);
        assertAggregate(dailyFor(fixture,localDayStart),SensorType.TEMPERATURE,1,"10",10.0,10.0,0);
        assertAggregate(dailyFor(fixture,localDayEnd),SensorType.TEMPERATURE,2,"50",20.0,30.0,0);
        assertCheckpoint(fixture,RAW_TO_HOURLY,sharedHour,nextHourlyCutoff);
        assertCheckpoint(fixture,HOURLY_TO_DAILY,localDayStart,nextLocalDayEnd);
        assertHourlySampleCount(fixture,3);
        List<Map<String,Object>> dailyBeforePurge = rows("daily_sensor_summaries",fixture);

        assertDeleted(purgeAt(purgeTime),3,0,0);

        assertRowCounts(fixture,0,25,2);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBeforePurge);
        assertIdempotentRollups(fixture,nextHourlyCutoff,nextLocalDayEnd);
    }





    private Fixture persistSensor(SensorType type,String timezone) {
        GeneratedSensorIngestionToken token = tokenGenerator.generate();
        Sensor sensor = new Sensor(owner,type,"Lifecycle sensor","Istanbul","Kadikoy","Window",timezone,CREATED_AT);
        sensor.assignIngestionTokenHash(token.tokenHash(),CREATED_AT);
        sensor = sensorRepository.saveAndFlush(sensor);
        return new Fixture(sensor.getId(),type,token.rawToken());
    }




    private void ingest(Fixture fixture,double numericValue,boolean booleanValue,Instant recordedAt) {
        switch (fixture.type()) {
            case TEMPERATURE -> ingestionService.ingestTemperature(fixture.rawToken(),numericValue,recordedAt);
            case HUMIDITY -> ingestionService.ingestHumidity(fixture.rawToken(),numericValue,recordedAt);
            case MOTION -> ingestionService.ingestMotion(fixture.rawToken(),booleanValue,recordedAt);
        }
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }




    private void rollUpHourly(Instant cutoff,int expectedBuckets,long expectedSamples) {
        HourlyRollupRunResult result = hourlyService.rollUpClosedHours(cutoff);

        assertThat(result.status()).isEqualTo(expectedBuckets == 0
                ? HourlyRollupRunResult.Status.NO_WORK
                : HourlyRollupRunResult.Status.SUCCEEDED);
        assertThat(result.sensorCount()).isEqualTo(1);
        assertThat(result.advancedBuckets()).isEqualTo(expectedBuckets);
        assertThat(result.sourceRowsSummarized()).isEqualTo(expectedSamples);
        assertThat(result.failedSensors()).isZero();
        assertThat(result.bounded()).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }




    private DailyRollupRunResult rollUpDaily(Instant cutoff,int expectedBuckets,long expectedSamples) {
        DailyRollupRunResult result = dailyService.rollUpClosedLocalDays(cutoff);

        assertThat(result.status()).isEqualTo(expectedBuckets == 0
                ? DailyRollupRunResult.Status.NO_WORK
                : DailyRollupRunResult.Status.SUCCEEDED);
        assertThat(result.sensorCount()).isEqualTo(1);
        assertThat(result.advancedBuckets()).isEqualTo(expectedBuckets);
        assertThat(result.sourceRowsSummarized()).isEqualTo(expectedSamples);
        assertThat(result.waitingSensors()).isZero();
        assertThat(result.failedSensors()).isZero();
        assertThat(result.bounded()).isFalse();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return result;
    }




    private SensorDataRetentionRunResult purgeAt(Instant currentTime) {
        at(currentTime);
        SensorDataRetentionRunResult result = retentionService.purgeExpiredData(currentTime);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        return result;
    }




    private void assertIdempotentRollups(Fixture fixture,Instant hourlyCutoff,Instant dailyCutoff) {
        List<Map<String,Object>> hourlyBefore = rows("hourly_sensor_summaries",fixture);
        List<Map<String,Object>> dailyBefore = rows("daily_sensor_summaries",fixture);
        List<Map<String,Object>> checkpointsBefore = rows("sensor_rollup_checkpoints",fixture);

        rollUpHourly(hourlyCutoff,0,0);
        rollUpDaily(dailyCutoff,0,0);

        assertThat(rows("hourly_sensor_summaries",fixture)).containsExactlyElementsOf(hourlyBefore);
        assertThat(rows("daily_sensor_summaries",fixture)).containsExactlyElementsOf(dailyBefore);
        assertThat(rows("sensor_rollup_checkpoints",fixture)).containsExactlyElementsOf(checkpointsBefore);
    }




    private static void assertDeleted(SensorDataRetentionRunResult result,long raw,long hourly,long daily) {
        long total = raw + hourly + daily;
        assertThat(result.status()).isEqualTo(total == 0
                ? SensorDataRetentionRunResult.Status.NO_WORK
                : SensorDataRetentionRunResult.Status.SUCCEEDED);
        assertThat(result.rawReadings().rowsDeleted()).isEqualTo(raw);
        assertThat(result.hourlySummaries().rowsDeleted()).isEqualTo(hourly);
        assertThat(result.dailySummaries().rowsDeleted()).isEqualTo(daily);
        assertThat(result.totalRowsDeleted()).isEqualTo(total);

        for (SensorDataPurgeTierResult tier : List.of(result.rawReadings(),result.hourlySummaries(),result.dailySummaries())) {
            assertThat(tier.failedSensors()).isZero();
            assertThat(tier.bounded()).isFalse();
        }
    }




    private void assertRowCounts(Fixture fixture,int raw,int hourly,int daily) {
        assertThat(rows("sensor_readings",fixture)).hasSize(raw);
        assertThat(rows("hourly_sensor_summaries",fixture)).hasSize(hourly);
        assertThat(rows("daily_sensor_summaries",fixture)).hasSize(daily);
    }



    private void assertHourlySampleCount(Fixture fixture,long expected) {
        Long samples = jdbcTemplate.queryForObject(
                "SELECT SUM(source_sample_count) FROM hourly_sensor_summaries WHERE sensor_id=?",
                Long.class,fixture.sensorId());
        assertThat(samples).isEqualTo(expected);
    }



    private void assertFirstReading(Fixture fixture,Instant expected) {
        Sensor sensor = sensorRepository.findById(fixture.sensorId()).orElseThrow();
        assertThat(sensor.getFirstReadingAt()).isEqualTo(expected);
        assertThat(sensor.hasRecordedReadings()).isTrue();
    }



    private void assertCheckpoint(Fixture fixture,RollupStage stage,Instant coverageStart,Instant coveredUntil) {
        SensorRollupCheckpoint checkpoint =
                checkpointRepository.findBySensorIdAndStage(fixture.sensorId(),stage).orElseThrow();
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(coverageStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(coveredUntil);
    }



    private static void assertAggregate(
            SensorSummary summary,SensorType type,long samples,String sum,double minimum,double maximum,long trueSamples) {
        assertThat(summary.getSourceSampleCount()).isEqualTo(samples);

        if (type == SensorType.MOTION) {
            assertThat(summary.getTrueSampleCount()).isEqualTo(trueSamples);
            assertThat(summary.getUnit()).isNull();
            assertThat(summary.getNumericSum()).isNull();
            assertThat(summary.getNumericMinimum()).isNull();
            assertThat(summary.getNumericMaximum()).isNull();
        } else {
            MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
            assertThat(summary.getUnit()).isEqualTo(unit);
            assertThat(summary.getNumericSum()).isEqualByComparingTo(sum);
            assertThat(summary.getNumericMinimum()).isEqualTo(minimum);
            assertThat(summary.getNumericMaximum()).isEqualTo(maximum);
            assertThat(summary.getTrueSampleCount()).isNull();
        }
    }



    private HourlySensorSummary hourlyFor(Fixture fixture,Instant bucketStart) {
        return hourlyRepository.findBySensorIdAndBucketStart(fixture.sensorId(),bucketStart).orElseThrow();
    }



    private DailySensorSummary dailyFor(Fixture fixture,Instant bucketStart) {
        return dailyRepository.findBySensorIdAndBucketStart(fixture.sensorId(),bucketStart).orElseThrow();
    }



    private List<Map<String,Object>> rows(String table,Fixture fixture) {
        return jdbcTemplate.queryForList("SELECT * FROM " + table + " WHERE sensor_id=? ORDER BY id",fixture.sensorId());
    }



    private void at(Instant instant) {
        doReturn(instant).when(clock).instant();
    }



    private record Fixture(Long sensorId,SensorType type,String rawToken) {}
}