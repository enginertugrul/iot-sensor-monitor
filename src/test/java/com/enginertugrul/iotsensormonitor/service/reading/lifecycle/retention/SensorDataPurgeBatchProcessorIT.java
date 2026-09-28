package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention;

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
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.CoverageBlocker;
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
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.InvalidDataAccessApiUsageException;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deferredByConcurrentWork;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deleted;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.noExpiredRows;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.waitingForCoverage;
import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;



@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-test.properties",
        "spring.datasource.hikari.connection-init-sql=SET statement_timeout TO '5s'"
})
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class SensorDataPurgeBatchProcessorIT {

    private static final Instant START = Instant.parse("2026-01-10T00:00:00Z");
    private static final Instant CUTOFF = Instant.parse("2026-01-13T00:00:00Z");

    @Autowired
    private SensorDataPurgeBatchProcessor processor;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoSpyBean
    private SensorReadingRepository readingRepository;

    @MockitoSpyBean
    private HourlySensorSummaryRepository hourlyRepository;

    @MockitoSpyBean
    private DailySensorSummaryRepository dailyRepository;

    private TransactionTemplate transactions;
    private AppUser owner;



    @BeforeEach
    void setUp() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        transactions = new TransactionTemplate(transactionManager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(20);

        owner = appUserRepository.saveAndFlush(new AppUser(
                "retention-" + UUID.randomUUID() + "@example.com","test-password-hash",START.minusSeconds(86400)));
    }



    @AfterEach
    void tearDown() {
        resetRepositories();

        if (owner != null) {
            transactions.executeWithoutResult(status -> {
                jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",owner.getId());
                jdbcTemplate.update("DELETE FROM app_users WHERE id=?",owner.getId());
            });
        }
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void deletesOldestRowsWithinTheBatchLimitAndHonorsTierCutoffs(Tier tier) {
        Sensor sensor = persistSensor("UTC");
        Sensor other = persistSensor("UTC");
        seedRequiredCoverage(tier,sensor);

        Long newer = insertRow(tier,sensor,CUTOFF.minusSeconds(tier.stepSeconds));
        Long oldest = insertRow(tier,sensor,CUTOFF.minusSeconds(2 * tier.stepSeconds));
        Long atBoundary = insertRow(tier,sensor,CUTOFF);
        Long afterBoundary = insertRow(tier,sensor,CUTOFF.plusSeconds(tier.stepSeconds));
        Long foreign = insertRow(tier,other,CUTOFF.minusSeconds(2 * tier.stepSeconds));
        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);

        assertThat(purge(tier,sensor,CUTOFF,1))
                .isEqualTo(deleted(sensor.getId(),1,true,CoverageBlocker.NONE));

        assertThat(rows(tier,sensor)).containsExactly(newer,atBoundary,afterBoundary);
        assertThat(rows(tier,sensor)).doesNotContain(oldest);
        assertThat(rows(tier,other)).containsExactly(foreign);

        int remainingExpiredRows = tier == Tier.RAW ? 1 : 2;

        assertThat(purge(tier,sensor,CUTOFF,10))
                .isEqualTo(deleted(sensor.getId(),remainingExpiredRows,false,CoverageBlocker.NONE));

        List<Long> retained = tier == Tier.RAW
                ? List.of(atBoundary,afterBoundary)
                : List.of(afterBoundary);

        assertThat(rows(tier,sensor)).containsExactlyElementsOf(retained);
        assertThat(rows(tier,other)).containsExactly(foreign);
        assertThat(purge(tier,sensor,CUTOFF,10)).isEqualTo(noExpiredRows(sensor.getId(),false));
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);
        assertThat(sensorRepository.findById(sensor.getId()).orElseThrow().getFirstReadingAt()).isEqualTo(START);
    }



    @ParameterizedTest
    @CsvSource({
            "RAW,RAW_TO_HOURLY,RAW_TO_HOURLY",
            "RAW,HOURLY_TO_DAILY,HOURLY_TO_DAILY",
            "HOURLY,HOURLY_TO_DAILY,HOURLY_TO_DAILY"
    })
    void preservesExpiredRowsWhenARequiredCheckpointIsMissing(Tier tier,RollupStage missingStage,CoverageBlocker blocker) {
        Sensor sensor = persistSensor("UTC");
        seedRequiredCoverage(tier,sensor);
        Long rowId = insertRow(tier,sensor,CUTOFF.minusSeconds(tier.stepSeconds));

        jdbcTemplate.update("DELETE FROM sensor_rollup_checkpoints WHERE sensor_id=? AND stage=?",
                sensor.getId(),missingStage.name());

        assertThat(purge(tier,sensor,CUTOFF,10))
                .isEqualTo(waitingForCoverage(sensor.getId(),false,blocker));

        assertThat(rows(tier,sensor)).containsExactly(rowId);
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void deletesOnlySafeRowsAndPreservesTheFractionalDailyBoundaryHour(Tier tier) {
        Sensor sensor = persistSensor("Asia/Kathmandu");
        Instant dailyCoveredUntil = Instant.parse("2026-01-12T18:15:00Z");
        Instant safeHourBoundary = Instant.parse("2026-01-12T18:00:00Z");

        if (tier == Tier.RAW) {
            seedCoverage(sensor,RollupStage.RAW_TO_HOURLY,CUTOFF);
        }
        seedCoverage(sensor,RollupStage.HOURLY_TO_DAILY,dailyCoveredUntil);

        Instant safeTime = tier == Tier.RAW ? safeHourBoundary.minusNanos(1000) : safeHourBoundary;
        Instant unsafeTime = tier == Tier.RAW ? safeHourBoundary : safeHourBoundary.plusSeconds(3600);
        Long safeId = insertRow(tier,sensor,safeTime);

        List<Long> protectedIds = new ArrayList<>();
        protectedIds.add(insertRow(tier,sensor,unsafeTime));
        if (tier == Tier.RAW) {
            protectedIds.add(insertRow(tier,sensor,dailyCoveredUntil.minusNanos(1000)));
        }

        List<Map<String,Object>> checkpointsBefore = checkpointRows(sensor);

        assertThat(purge(tier,sensor,CUTOFF,10))
                .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.HOURLY_TO_DAILY));

        assertThat(rows(tier,sensor)).doesNotContain(safeId);
        assertThat(rows(tier,sensor)).containsExactlyElementsOf(protectedIds);

        assertThat(purge(tier,sensor,CUTOFF,10))
                .isEqualTo(waitingForCoverage(sensor.getId(),false,CoverageBlocker.HOURLY_TO_DAILY));

        assertThat(rows(tier,sensor)).containsExactlyElementsOf(protectedIds);
        assertThat(checkpointRows(sensor)).containsExactlyElementsOf(checkpointsBefore);
    }



    @Test
    void rawDeletionStopsAtHourlyCoverageEvenWhenDailyCoverageIsComplete() {
        Sensor sensor = persistSensor("UTC");
        Instant hourlyCoveredUntil = CUTOFF.minusSeconds(3600);
        seedCoverage(sensor,RollupStage.RAW_TO_HOURLY,hourlyCoveredUntil);
        seedCoverage(sensor,RollupStage.HOURLY_TO_DAILY,CUTOFF);

        Long safe = insertRow(Tier.RAW,sensor,hourlyCoveredUntil.minusNanos(1000));
        Long protectedRow = insertRow(Tier.RAW,sensor,hourlyCoveredUntil);

        assertThat(processor.purgeRawReadings(sensor.getId(),CUTOFF,10))
                .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.RAW_TO_HOURLY));

        assertThat(rows(Tier.RAW,sensor)).containsExactly(protectedRow).doesNotContain(safe);
        assertThat(processor.purgeRawReadings(sensor.getId(),CUTOFF,10))
                .isEqualTo(waitingForCoverage(sensor.getId(),false,CoverageBlocker.RAW_TO_HOURLY));
    }



    @Test
    void dailyDeletionUsesTheExactInclusiveLocalDayEndWithoutRequiringCheckpoints() {
        Sensor sensor = persistSensor("Asia/Kathmandu");
        Instant bucketEnd = Instant.parse("2026-01-12T18:15:00Z");
        Long expired = insertRow(Tier.DAILY,sensor,bucketEnd);
        Long retained = insertRow(Tier.DAILY,sensor,bucketEnd.plusSeconds(86400));

        assertThat(processor.purgeDailySummaries(sensor.getId(),bucketEnd.minusNanos(1000),10))
                .isEqualTo(noExpiredRows(sensor.getId(),false));
        assertThat(rows(Tier.DAILY,sensor)).containsExactly(expired,retained);

        assertThat(processor.purgeDailySummaries(sensor.getId(),bucketEnd,10))
                .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.NONE));
        assertThat(rows(Tier.DAILY,sensor)).containsExactly(retained);
        assertThat(checkpointRows(sensor)).isEmpty();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void skipsLockedRowsReportsDeferralAndRetriesAfterLockRelease(Tier tier) {
        Sensor sensor = persistSensor("UTC");
        seedRequiredCoverage(tier,sensor);
        Long oldest = insertRow(tier,sensor,CUTOFF.minusSeconds(2 * tier.stepSeconds));
        Long available = insertRow(tier,sensor,CUTOFF.minusSeconds(tier.stepSeconds));

        transactions.executeWithoutResult(status -> {
            Long lockedId = jdbcTemplate.queryForObject(
                    "SELECT id FROM " + tier.table + " WHERE id=? FOR UPDATE",Long.class,oldest);
            assertThat(lockedId).isEqualTo(oldest);

            assertThat(purge(tier,sensor,CUTOFF,1))
                    .isEqualTo(deleted(sensor.getId(),1,true,CoverageBlocker.NONE));
            assertThat(rows(tier,sensor)).containsExactly(oldest).doesNotContain(available);

            assertThat(purge(tier,sensor,CUTOFF,1))
                    .isEqualTo(deferredByConcurrentWork(sensor.getId()));
            assertThat(rows(tier,sensor)).containsExactly(oldest);
        });

        assertThat(purge(tier,sensor,CUTOFF,1))
                .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.NONE));
        assertThat(rows(tier,sensor)).isEmpty();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void rollsBackAnExecutedDeleteWhenThePostDeleteCheckFailsAndAllowsRetry(Tier tier) {
        Sensor sensor = persistSensor("UTC");
        seedRequiredCoverage(tier,sensor);
        Long rowId = insertRow(tier,sensor,CUTOFF.minusSeconds(tier.stepSeconds));
        RuntimeException failure = new IllegalStateException("Forced post-delete failure");

        Answer<Boolean> failAfterDeletion = invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(rows(tier,sensor)).isEmpty();
            throw failure;
        };

        switch (tier) {
            case RAW -> doReturn(true).doAnswer(failAfterDeletion).when(readingRepository)
                    .existsEligibleForRetentionPurge(sensor.getId(),CUTOFF);
            case HOURLY -> doReturn(true).doAnswer(failAfterDeletion).when(hourlyRepository)
                    .existsEligibleForRetentionPurge(sensor.getId(),CUTOFF);
            case DAILY -> doReturn(true).doAnswer(failAfterDeletion).when(dailyRepository)
                    .existsBySensorIdAndBucketEndLessThanEqual(sensor.getId(),CUTOFF);
        }

        assertThatThrownBy(() -> purge(tier,sensor,CUTOFF,10))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessage("Forced post-delete failure")
                .cause().isSameAs(failure);

        resetRepositories();

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(rows(tier,sensor)).containsExactly(rowId);

        assertThat(purge(tier,sensor,CUTOFF,10))
                .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.NONE));
        assertThat(rows(tier,sensor)).isEmpty();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void commitsDeletionEvenWhenTheCallingTransactionRollsBack(Tier tier) {
        Sensor sensor = persistSensor("UTC");
        seedRequiredCoverage(tier,sensor);
        insertRow(tier,sensor,CUTOFF.minusSeconds(tier.stepSeconds));

        transactions.executeWithoutResult(status -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();

            assertThat(purge(tier,sensor,CUTOFF,10))
                    .isEqualTo(deleted(sensor.getId(),1,false,CoverageBlocker.NONE));

            status.setRollbackOnly();
        });

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(rows(tier,sensor)).isEmpty();
    }



    private Sensor persistSensor(String timezone) {
        Sensor sensor = new Sensor(
                owner,SensorType.TEMPERATURE,"Retention " + UUID.randomUUID(),
                "Istanbul","Kadikoy","Window",timezone,START.minusSeconds(3600));
        sensor.recordFirstReading(START,TEST_INSTANT);
        return sensorRepository.saveAndFlush(sensor);
    }



    private void seedRequiredCoverage(Tier tier,Sensor sensor) {
        if (tier == Tier.RAW) {
            seedCoverage(sensor,RollupStage.RAW_TO_HOURLY,CUTOFF);
        }
        if (tier != Tier.DAILY) {
            seedCoverage(sensor,RollupStage.HOURLY_TO_DAILY,CUTOFF);
        }
    }



    private void seedCoverage(Sensor sensor,RollupStage stage,Instant coveredUntil) {
        ZoneId zone = ZoneId.of(sensor.getTimezone());
        Instant coverageStart = stage == RollupStage.RAW_TO_HOURLY
                ? sensor.getFirstReadingAt().truncatedTo(ChronoUnit.HOURS)
                : sensor.getFirstReadingAt().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();

        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(
                sensor,stage,coverageStart,TEST_INSTANT);

        while (checkpoint.getCoveredUntil().isBefore(coveredUntil)) {
            Instant start = checkpoint.getCoveredUntil();
            Instant end = stage == RollupStage.RAW_TO_HOURLY
                    ? start.plusSeconds(3600)
                    : start.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant();

            assertThat(end).isBeforeOrEqualTo(coveredUntil);
            checkpoint.recordAttempt(start,TEST_INSTANT);
            checkpoint.advanceContiguously(start,end,TEST_INSTANT);
        }

        checkpointRepository.saveAndFlush(checkpoint);
    }



    private Long insertRow(Tier tier,Sensor sensor,Instant orderingTime) {
        return switch (tier) {
            case RAW -> readingRepository.saveAndFlush(
                    SensorReading.temperature(sensor,20.0,orderingTime)).getId();
            case HOURLY -> hourlyRepository.saveAndFlush(
                    HourlySensorSummary.create(sensor,orderingTime.minusSeconds(3600),aggregate(),TEST_INSTANT)).getId();
            case DAILY -> {
                ZoneId zone = ZoneId.of(sensor.getTimezone());
                LocalDate date = orderingTime.atZone(zone).toLocalDate().minusDays(1);
                yield dailyRepository.saveAndFlush(
                        DailySensorSummary.create(sensor,date,zone,aggregate(),TEST_INSTANT)).getId();
            }
        };
    }



    private SensorSummaryAggregate aggregate() {
        return SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,20.0);
    }



    private SensorDataPurgeBatchResult purge(Tier tier,Sensor sensor,Instant cutoff,int batchSize) {
        return switch (tier) {
            case RAW -> processor.purgeRawReadings(sensor.getId(),cutoff,batchSize);
            case HOURLY -> processor.purgeHourlySummaries(sensor.getId(),cutoff,batchSize);
            case DAILY -> processor.purgeDailySummaries(sensor.getId(),cutoff,batchSize);
        };
    }



    private List<Long> rows(Tier tier,Sensor sensor) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM " + tier.table + " WHERE sensor_id=? ORDER BY " + tier.orderColumn + ",id",
                Long.class,sensor.getId());
    }



    private List<Map<String,Object>> checkpointRows(Sensor sensor) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM sensor_rollup_checkpoints WHERE sensor_id=? ORDER BY stage",sensor.getId());
    }



    private void resetRepositories() {
        reset(readingRepository,hourlyRepository,dailyRepository);
    }



    private enum Tier {
        RAW("sensor_readings","recorded_at",3600),
        HOURLY("hourly_sensor_summaries","bucket_end",3600),
        DAILY("daily_sensor_summaries","bucket_end",86400);

        private final String table;
        private final String orderColumn;
        private final long stepSeconds;

        Tier(String table,String orderColumn,long stepSeconds) {
            this.table = table;
            this.orderColumn = orderColumn;
            this.stepSeconds = stepSeconds;
        }
    }
}