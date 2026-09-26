package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
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
import com.enginertugrul.iotsensormonitor.entity.user.EmailVerificationChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.testsupport.PostgresTestConfiguration;
import com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class RepositoryConcurrencyIT {

    private static final Instant BASE = Instant.parse("2026-01-16T00:00:00Z");
    private static final Instant COVERAGE_START = BASE.minusSeconds(86400);
    private static final Instant COVERED_UNTIL = BASE.plusSeconds(8 * 86400);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private EmailVerificationChallengeRepository emailChallengeRepository;

    @Autowired
    private PasswordResetChallengeRepository passwordChallengeRepository;

    @Autowired
    private SensorRollupCheckpointRepository checkpointRepository;

    @Autowired
    private SensorReadingRepository readingRepository;

    @Autowired
    private HourlySensorSummaryRepository hourlyRepository;

    @Autowired
    private DailySensorSummaryRepository dailyRepository;

    private TransactionTemplate transactions;
    private ExecutorService executor;
    private Fixture fixture;



    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(40);

        executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task,"repository-lock-holder");
            thread.setDaemon(true);
            return thread;
        });

        fixture = inTransaction(() -> {
            String suffix = UUID.randomUUID().toString().replace("-","");
            String email = "concurrency-" + suffix + "@example.com";
            String tokenHash = suffix.repeat(2);


            AppUser owner = new AppUser(email,"test-password-hash",CREATED_AT);
            owner.verifyEmail(CREATED_AT.plusSeconds(60));
            appUserRepository.saveAndFlush(owner);

            Sensor target = newSensor(owner,"Target");
            target.assignIngestionTokenHash(tokenHash,CREATED_AT.plusSeconds(60));
            sensorRepository.saveAndFlush(target);

            Sensor other = sensorRepository.saveAndFlush(newSensor(owner,"Other"));

            return new Fixture(owner.getId(),email,target.getId(),other.getId(),tokenHash);
        });
    }



    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15,TimeUnit.SECONDS))
                    .as("Background transactions must finish before fixture cleanup")
                    .isTrue();
        }

        if (fixture != null) {
            inTransaction(() -> {
                jdbcTemplate.update("DELETE FROM sensors WHERE owner_id = ?",fixture.userId());
                return jdbcTemplate.update("DELETE FROM app_users WHERE id = ?",fixture.userId());
            });
        }
    }



    @ParameterizedTest(name = "{0}, rollback={1}")
    @MethodSource("lockCompletionModes")
    void holdsWriteLocksUntilTransactionCompletion(LockedLookup lookup,boolean rollback) throws Exception {
        LockRows rows = inTransaction(() -> prepareLockRows(lookup));

        whileTransactionIsOpen(() -> {
            assertThat(lockThroughRepository(lookup)).containsExactlyElementsOf(rows.ids());
        },rollback,holderPid -> {
            for (Long rowId : rows.ids()) {
                DataAccessException exception = assertThrows(DataAccessException.class,() -> inTransaction(() -> {
                    assertDifferentBackend(holderPid);
                    return jdbcTemplate.queryForObject(
                            "SELECT id FROM " + rows.table() + " WHERE id = ? FOR SHARE NOWAIT",Long.class,rowId);
                }));

                assertSqlState(exception,"55P03");
            }
        });

        for (Long rowId : rows.ids()) {
            Long availableId = inTransaction(() -> jdbcTemplate.queryForObject(
                    "SELECT id FROM " + rows.table() + " WHERE id = ? FOR SHARE NOWAIT",Long.class,rowId));

            assertThat(availableId).isEqualTo(rowId);
        }

        List<Long> reloadedIds = inTransaction(() -> lockThroughRepository(lookup));
        assertThat(reloadedIds).containsExactlyElementsOf(rows.ids());
    }



    @ParameterizedTest
    @EnumSource(RetentionTier.class)
    void deletesOnlyOldestEligibleRowsWithinEachBatch(RetentionTier tier) {
        PurgeData data = preparePurgeData(tier);
        List<Long> deletedIds = new ArrayList<>();

        for (int offset = 0; offset < data.eligibleIds().size(); offset += 2) {
            int expectedCount = Math.min(2,data.eligibleIds().size() - offset);

            assertThat(deleteBatch(data,2)).isEqualTo(expectedCount);

            deletedIds.addAll(data.eligibleIds().subList(offset,offset + expectedCount));
            assertRemainingRows(data,deletedIds);
        }

        assertThat(deleteBatch(data,2)).isZero();
        assertRemainingRows(data,data.eligibleIds());
    }



    @ParameterizedTest
    @EnumSource(RetentionTier.class)
    void skipsLockedOldestRowsAndDeletesThemAfterTheirLockIsReleased(RetentionTier tier) throws Exception {
        PurgeData data = preparePurgeData(tier);
        Long lockedId = data.eligibleIds().getFirst();

        whileTransactionIsOpen(() -> lockRow(tier.table,lockedId),false,holderPid -> {
            int firstBatch = inTransaction(() -> {
                assertDifferentBackend(holderPid);
                return deleteFromRepository(data,2);
            });

            assertThat(firstBatch).isEqualTo(2);
            assertRemainingRows(data,data.eligibleIds().subList(1,3));

            assertThat(deleteBatch(data,100)).isEqualTo(data.eligibleIds().size() - 3);
            assertRemainingRows(data,data.eligibleIds().subList(1,data.eligibleIds().size()));

            assertThat(deleteBatch(data,2)).isZero();
            assertRemainingRows(data,data.eligibleIds().subList(1,data.eligibleIds().size()));
        });

        assertThat(deleteBatch(data,2)).isEqualTo(1);
        assertThat(deleteBatch(data,2)).isZero();
        assertRemainingRows(data,data.eligibleIds());
    }



    @ParameterizedTest(name = "{0}, rollback first purge={1}")
    @MethodSource("purgeCompletionModes")
    void overlappingPurgeTransactionsDeleteDisjointBatches(RetentionTier tier,boolean rollbackFirst) throws Exception {
        PurgeData data = preparePurgeData(tier);
        List<Long> firstBatchIds = data.eligibleIds().subList(0,2);
        List<Long> secondBatchIds = data.eligibleIds().subList(2,4);

        whileTransactionIsOpen(() -> {
            assertThat(deleteFromRepository(data,2)).isEqualTo(2);
        },rollbackFirst,holderPid -> {
            int secondBatch = inTransaction(() -> {
                assertDifferentBackend(holderPid);
                return deleteFromRepository(data,2);
            });

            assertThat(secondBatch).isEqualTo(2);

            // The first transaction has not committed, so its deleted rows remain visible here.
            assertRemainingRows(data,secondBatchIds);
        });

        List<Long> committedDeletedIds = new ArrayList<>(secondBatchIds);
        if (!rollbackFirst) {
            committedDeletedIds.addAll(firstBatchIds);
        }

        assertRemainingRows(data,committedDeletedIds);

        int remainingEligibleCount = data.eligibleIds().size() - committedDeletedIds.size();
        assertThat(deleteBatch(data,100)).isEqualTo(remainingEligibleCount);
        assertThat(deleteBatch(data,100)).isZero();
        assertRemainingRows(data,data.eligibleIds());
    }



    @ParameterizedTest(name = "{0} requires {1}")
    @MethodSource("requiredCheckpointCases")
    void preservesRowsWhenARequiredCheckpointIsMissing(RetentionTier tier,RollupStage missingStage) {
        PurgeData data = preparePurgeData(tier);

        inTransaction(() -> {
            SensorRollupCheckpoint checkpoint = checkpointRepository
                    .findBySensorIdAndStage(fixture.sensorId(),missingStage).orElseThrow();
            checkpointRepository.delete(checkpoint);
            checkpointRepository.flush();
            return null;
        });

        assertThat(deleteBatch(data,100)).isZero();
        assertRemainingRows(data,List.of());
    }



    private LockRows prepareLockRows(LockedLookup lookup) {
        return switch (lookup) {
            case USER_BY_ID,USER_BY_EMAIL -> new LockRows("app_users",List.of(fixture.userId()));
            case SENSOR_BY_ID,SENSOR_BY_OWNER,SENSOR_BY_TOKEN ->
                    new LockRows("sensors",List.of(fixture.sensorId()));
            case ALERT_RULES -> new LockRows("alert_rules",List.of(insertAlertRule(),insertAlertRule()));
            case EMAIL_CHALLENGE -> {
                AppUser owner = appUserRepository.findById(fixture.userId()).orElseThrow();
                EmailVerificationChallenge challenge = emailChallengeRepository.saveAndFlush(
                        new EmailVerificationChallenge(owner,"verification-test-hash",BASE,BASE.plusSeconds(600),BASE.plusSeconds(60)));
                yield new LockRows("email_verification_challenges",List.of(challenge.getId()));
            }
            case PASSWORD_CHALLENGE -> {
                AppUser owner = appUserRepository.findById(fixture.userId()).orElseThrow();
                PasswordResetChallenge challenge = passwordChallengeRepository.saveAndFlush(
                        new PasswordResetChallenge(owner,"recovery-test-hash",BASE,BASE.plusSeconds(600),BASE.plusSeconds(60)));
                yield new LockRows("password_reset_challenges",List.of(challenge.getId()));
            }
            case HOURLY_CHECKPOINT,DAILY_CHECKPOINT -> {
                Sensor sensor = sensorRepository.findById(fixture.sensorId()).orElseThrow();
                RollupStage stage = lookup == LockedLookup.HOURLY_CHECKPOINT
                        ? RollupStage.RAW_TO_HOURLY : RollupStage.HOURLY_TO_DAILY;
                SensorRollupCheckpoint checkpoint = checkpointRepository.saveAndFlush(
                        SensorRollupCheckpoint.initialize(sensor,stage,BASE,BASE));
                yield new LockRows("sensor_rollup_checkpoints",List.of(checkpoint.getId()));
            }
        };
    }



    private List<Long> lockThroughRepository(LockedLookup lookup) {
        return switch (lookup) {
            case USER_BY_ID ->
                    List.of(appUserRepository.findByIdForUpdate(fixture.userId()).orElseThrow().getId());
            case USER_BY_EMAIL ->
                    List.of(appUserRepository.findByEmailForUpdate(fixture.email()).orElseThrow().getId());
            case SENSOR_BY_ID ->
                    List.of(sensorRepository.findByIdForUpdate(fixture.sensorId()).orElseThrow().getId());
            case SENSOR_BY_OWNER ->
                    List.of(sensorRepository.findByIdAndOwnerIdForUpdate(fixture.sensorId(),fixture.userId()).orElseThrow().getId());
            case SENSOR_BY_TOKEN ->
                    List.of(sensorRepository.findByIngestionTokenHashForUpdate(fixture.tokenHash()).orElseThrow().getId());
            case ALERT_RULES ->
                    alertRuleRepository.findEnabledForEvaluationBySensorId(fixture.sensorId()).stream().map(AlertRule::getId).toList();
            case EMAIL_CHALLENGE ->
                    List.of(emailChallengeRepository.findByUserIdForUpdate(fixture.userId()).orElseThrow().getId());
            case PASSWORD_CHALLENGE ->
                    List.of(passwordChallengeRepository.findByUserIdForUpdate(fixture.userId()).orElseThrow().getId());
            case HOURLY_CHECKPOINT ->
                    List.of(checkpointRepository.findBySensorIdAndStageForUpdate(
                            fixture.sensorId(),RollupStage.RAW_TO_HOURLY).orElseThrow().getId());
            case DAILY_CHECKPOINT ->
                    List.of(checkpointRepository.findBySensorIdAndStageForUpdate(
                            fixture.sensorId(),RollupStage.HOURLY_TO_DAILY).orElseThrow().getId());
        };
    }



    private Long insertAlertRule() {
        Timestamp timestamp = Timestamp.from(BASE);

        return jdbcTemplate.queryForObject("""
                INSERT INTO alert_rules (
                    owner_id,sensor_id,rule_type,comparison_operator,threshold_value,threshold_unit,
                    enabled,cooldown_minutes,created_at,updated_at
                )
                VALUES (?,?,'NUMERIC_THRESHOLD','ABOVE',20.0,'C',true,60,?,?)
                RETURNING id
                """,Long.class,fixture.userId(),fixture.sensorId(),timestamp,timestamp);
    }



    private PurgeData preparePurgeData(RetentionTier tier) {
        return inTransaction(() -> {
            Sensor target = sensorRepository.findById(fixture.sensorId()).orElseThrow();
            Sensor other = sensorRepository.findById(fixture.otherSensorId()).orElseThrow();

            if (tier != RetentionTier.DAILY) {
                persistCoverage(target);
                persistCoverage(other);
            }

            long step = tier.stepSeconds;
            Instant cutoff = BASE.plusSeconds(4 * step);

            // Insert out of chronological order so ordering cannot accidentally follow identity values.
            Long middle = insertDataRow(tier,target,BASE.plusSeconds(2 * step));
            Long oldest = insertDataRow(tier,target,BASE);
            Long newest = insertDataRow(tier,target,BASE.plusSeconds(3 * step));
            Long firstAvailable = insertDataRow(tier,target,BASE.plusSeconds(step));

            List<Long> allIds = new ArrayList<>();
            allIds.add(oldest);
            allIds.add(firstAvailable);

            if (tier == RetentionTier.RAW) {
                allIds.add(insertDataRow(tier,target,BASE.plusSeconds(step)));
            }

            allIds.add(middle);
            allIds.add(newest);

            Long atBoundary = insertDataRow(tier,target,cutoff);
            Long afterBoundary = insertDataRow(tier,target,cutoff.plusSeconds(step));
            allIds.add(atBoundary);
            allIds.add(afterBoundary);

            Long foreignId = insertDataRow(tier,other,BASE);
            List<Long> protectedIds = tier == RetentionTier.RAW
                    ? List.of(atBoundary,afterBoundary) : List.of(afterBoundary);
            List<Long> eligibleIds = allIds.stream().filter(id -> !protectedIds.contains(id)).toList();

            return new PurgeData(tier,cutoff,List.copyOf(allIds),eligibleIds,foreignId);
        });
    }



    private Long insertDataRow(RetentionTier tier,Sensor sensor,Instant orderingTime) {
        return switch (tier) {
            case RAW -> readingRepository.saveAndFlush(
                    SensorReading.temperature(sensor,20.0,orderingTime)).getId();
            case HOURLY -> hourlyRepository.saveAndFlush(
                    HourlySensorSummary.create(sensor,orderingTime.minusSeconds(3600),aggregate(),orderingTime)).getId();
            case DAILY -> {
                LocalDate localDate = orderingTime.atZone(UTC).toLocalDate().minusDays(1);
                yield dailyRepository.saveAndFlush(
                        DailySensorSummary.create(sensor,localDate,UTC,aggregate(),orderingTime)).getId();
            }
        };
    }



    private void persistCoverage(Sensor sensor) {
        for (RollupStage stage : RollupStage.values()) {
            long step = stage == RollupStage.RAW_TO_HOURLY ? 3600 : 86400;
            SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(
                    sensor,stage,COVERAGE_START,COVERED_UNTIL);

            for (Instant start = COVERAGE_START; start.isBefore(COVERED_UNTIL); start = start.plusSeconds(step)) {
                checkpoint.recordAttempt(start,COVERED_UNTIL);
                checkpoint.advanceContiguously(start,start.plusSeconds(step),COVERED_UNTIL);
            }

            checkpointRepository.saveAndFlush(checkpoint);
        }
    }



    private int deleteBatch(PurgeData data,int batchSize) {
        return inTransaction(() -> deleteFromRepository(data,batchSize));
    }

    private int deleteFromRepository(PurgeData data,int batchSize) {
        return switch (data.tier()) {
            case RAW -> readingRepository.deleteOldestEligibleRetentionBatch(
                    fixture.sensorId(),data.cutoff(),batchSize);
            case HOURLY -> hourlyRepository.deleteOldestEligibleRetentionBatch(
                    fixture.sensorId(),data.cutoff(),batchSize);
            case DAILY -> dailyRepository.deleteOldestRetentionBatch(
                    fixture.sensorId(),data.cutoff(),batchSize);
        };
    }



    private void assertRemainingRows(PurgeData data,Collection<Long> deletedIds) {
        List<Long> expectedIds = data.allIds().stream().filter(id -> !deletedIds.contains(id)).toList();
        String query = "SELECT id FROM " + data.tier().table
                + " WHERE sensor_id = ? ORDER BY " + data.tier().orderColumn + ",id";

        List<Long> actualIds = inTransaction(() -> jdbcTemplate.queryForList(query,Long.class,fixture.sensorId()));
        List<Long> foreignIds = inTransaction(() -> jdbcTemplate.queryForList(query,Long.class,fixture.otherSensorId()));

        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
        assertThat(foreignIds).containsExactly(data.foreignId());
    }



    private void lockRow(String table,Long id) {
        Long lockedId = jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE id = ? FOR UPDATE",Long.class,id);
        assertThat(lockedId).isEqualTo(id);
    }



    private void whileTransactionIsOpen(Runnable work,boolean rollback,IntConsumer competingWork) throws Exception {
        CompletableFuture<Integer> ready = new CompletableFuture<>();
        CountDownLatch release = new CountDownLatch(1);

        Future<?> holder = executor.submit(() -> {
            try {
                transactions.executeWithoutResult(status -> {
                    configureTransactionTimeouts();
                    work.run();
                    ready.complete(backendPid());
                    awaitRelease(release);

                    if (rollback) {
                        status.setRollbackOnly();
                    }
                });
            } catch (RuntimeException | Error exception) {
                ready.completeExceptionally(exception);
                throw exception;
            }
        });

        try {
            int holderPid = ready.get(20,TimeUnit.SECONDS);
            competingWork.accept(holderPid);
        } finally {
            release.countDown();
            holder.get(20,TimeUnit.SECONDS);
        }
    }



    private void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30,TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the background transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the background transaction",exception);
        }
    }



    private <T> T inTransaction(Supplier<T> work) {
        return transactions.execute(status -> {
            configureTransactionTimeouts();
            return work.get();
        });
    }



    private void configureTransactionTimeouts() {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '2s'");
        jdbcTemplate.execute("SET LOCAL statement_timeout = '10s'");
    }



    private int backendPid() {
        return jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
    }



    private void assertDifferentBackend(int holderPid) {
        assertThat(backendPid()).as("Competing work must use another PostgreSQL connection").isNotEqualTo(holderPid);
    }



    private void assertSqlState(DataAccessException exception,String expectedState) {
        assertThat(exception.getMostSpecificCause()).isInstanceOf(SQLException.class);
        SQLException sqlException = (SQLException) exception.getMostSpecificCause();
        assertThat(sqlException.getSQLState()).isEqualTo(expectedState);
    }



    private Sensor newSensor(AppUser owner,String name) {
        return new Sensor(owner,SensorType.TEMPERATURE,name,"Istanbul","Kadikoy","Window","UTC",CREATED_AT);
    }



    private SensorSummaryAggregate aggregate() {
        return SensorSummaryAggregate.numeric(2,MeasurementUnit.C,new BigDecimal("30"),10.0,20.0);
    }



    private static Stream<Arguments> lockCompletionModes() {
        return Arrays.stream(LockedLookup.values()).flatMap(lookup -> Stream.of(
                Arguments.of(lookup,false),
                Arguments.of(lookup,true)));
    }



    private static Stream<Arguments> purgeCompletionModes() {
        return Arrays.stream(RetentionTier.values()).flatMap(tier -> Stream.of(
                Arguments.of(tier,false),
                Arguments.of(tier,true)));
    }



    private static Stream<Arguments> requiredCheckpointCases() {
        return Stream.of(
                Arguments.of(RetentionTier.RAW,RollupStage.RAW_TO_HOURLY),
                Arguments.of(RetentionTier.RAW,RollupStage.HOURLY_TO_DAILY),
                Arguments.of(RetentionTier.HOURLY,RollupStage.HOURLY_TO_DAILY));
    }



    private enum LockedLookup {
        USER_BY_ID,
        USER_BY_EMAIL,
        SENSOR_BY_ID,
        SENSOR_BY_OWNER,
        SENSOR_BY_TOKEN,
        ALERT_RULES,
        EMAIL_CHALLENGE,
        PASSWORD_CHALLENGE,
        HOURLY_CHECKPOINT,
        DAILY_CHECKPOINT
    }



    private enum RetentionTier {
        RAW("sensor_readings","recorded_at",3600),
        HOURLY("hourly_sensor_summaries","bucket_end",3600),
        DAILY("daily_sensor_summaries","bucket_end",86400);

        private final String table;
        private final String orderColumn;
        private final long stepSeconds;

        RetentionTier(String table,String orderColumn,long stepSeconds) {
            this.table = table;
            this.orderColumn = orderColumn;
            this.stepSeconds = stepSeconds;
        }
    }



    private record Fixture(Long userId,String email,Long sensorId,Long otherSensorId,String tokenHash) {}



    private record LockRows(String table,List<Long> ids) {}



    private record PurgeData(
            RetentionTier tier,
            Instant cutoff,
            List<Long> allIds,
            List<Long> eligibleIds,
            Long foreignId) {}
}