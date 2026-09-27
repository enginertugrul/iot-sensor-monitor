package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AlertRuleRepository;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@Execution(ExecutionMode.SAME_THREAD)
class AlertEvaluationIT {

    private static final Instant CREATED_AT = TEST_INSTANT.minusSeconds(172800);
    private static final Instant RECORDED_AT = TEST_INSTANT.minusSeconds(7200).plusNanos(123456000);
    private static final int COOLDOWN_MINUTES = 10;

    @Autowired
    private AlertEvaluationService service;

    @Autowired
    private AlertRuleRepository alertRuleRepository;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private SensorRepository sensorRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AlertEvents events;

    @PersistenceContext
    private EntityManager entityManager;

    private final List<Long> fixtureOwnerIds = new ArrayList<>();
    private TransactionTemplate transactions;
    private ExecutorService executor;


    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(45);
        events.clear();
    }



    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30,TimeUnit.SECONDS))
                    .as("Concurrent transactions must finish before fixture cleanup")
                    .isTrue();
        }

        for (Long ownerId : fixtureOwnerIds) {
            jdbcTemplate.update("DELETE FROM sensors WHERE owner_id=?",ownerId);
            jdbcTemplate.update("DELETE FROM app_users WHERE id=?",ownerId);
        }
        events.clear();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void commitsCooldownUsingRecordedTimeAndAllowsExactExpiry(SensorType type) {
        AppUser owner = persistUser(true);
        Sensor sensor = persistSensor(owner,type,"Target");
        AlertRule rule = persistRule(sensor);

        evaluate(sensor.getId(),RECORDED_AT);

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(RECORDED_AT);
        assertThat(events.snapshot()).hasSize(1);
        AlertTriggeredEvent first = events.snapshot().getFirst();
        assertThat(first.context().alertRuleId()).isEqualTo(rule.getId().longValue());
        assertThat(first.context().sensor().id()).isEqualTo(sensor.getId().longValue());
        assertThat(first.context().sensor().type()).isEqualTo(type);
        assertThat(first.context().recordedAt()).isEqualTo(RECORDED_AT);
        assertThat(first.context().cooldownMinutes()).isEqualTo(COOLDOWN_MINUTES);

        Instant cooldownEndsAt = RECORDED_AT.plusSeconds(600);
        for (Instant suppressedAt : List.of(RECORDED_AT.minusSeconds(1),RECORDED_AT,cooldownEndsAt.minusNanos(1000))) {
            evaluate(sensor.getId(),suppressedAt);
            assertThat(lastTriggeredAt(rule.getId())).isEqualTo(RECORDED_AT);
        }
        assertThat(events.snapshot()).hasSize(1);

        evaluate(sensor.getId(),cooldownEndsAt);

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(cooldownEndsAt);
        assertThat(events.snapshot()).extracting(event -> event.context().recordedAt())
                .containsExactly(RECORDED_AT,cooldownEndsAt);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void excludesUnverifiedOwnerUntilVerificationIsCommitted(SensorType type) {
        AppUser owner = persistUser(false);
        Sensor sensor = persistSensor(owner,type,"Unverified owner sensor");
        AlertRule rule = persistRule(sensor);

        evaluate(sensor.getId(),RECORDED_AT);

        assertThat(lastTriggeredAt(rule.getId())).isNull();
        assertThat(events.snapshot()).isEmpty();

        transactions.executeWithoutResult(status ->
                appUserRepository.findById(owner.getId()).orElseThrow().verifyEmail(TEST_INSTANT));

        evaluate(sensor.getId(),RECORDED_AT);

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(RECORDED_AT);
        assertThat(events.snapshot()).hasSize(1);
        assertThat(events.snapshot().getFirst().context().recipient().email()).isEqualTo(owner.getEmail());
    }



    @Test
    void evaluatesAllMatchingEnabledRulesOnlyForTheRequestedSensor() {
        AppUser owner = persistUser(true);
        AppUser otherOwner = persistUser(true);
        Sensor target = persistSensor(owner,SensorType.TEMPERATURE,"Target");
        AlertRule first = persistRule(target);
        AlertRule second = alertRuleRepository.saveAndFlush(AlertRule.numericThreshold(target,ComparisonOperator.ABOVE,24.0,COOLDOWN_MINUTES));
        AlertRule nonMatching = alertRuleRepository.saveAndFlush(AlertRule.numericThreshold(target,ComparisonOperator.ABOVE,30.0,COOLDOWN_MINUTES));
        AlertRule disabled = persistRule(target);
        disabled.disable();
        alertRuleRepository.saveAndFlush(disabled);
        AlertRule sibling = persistRule(persistSensor(owner,SensorType.TEMPERATURE,"Sibling"));
        AlertRule foreign = persistRule(persistSensor(otherOwner,SensorType.TEMPERATURE,"Foreign"));

        evaluate(target.getId(),RECORDED_AT);

        assertThat(events.snapshot()).extracting(event -> event.context().alertRuleId())
                .containsExactly(first.getId(),second.getId());
        assertThat(lastTriggeredAt(first.getId())).isEqualTo(RECORDED_AT);
        assertThat(lastTriggeredAt(second.getId())).isEqualTo(RECORDED_AT);
        for (AlertRule untouched : List.of(nonMatching,disabled,sibling,foreign)) {
            assertThat(lastTriggeredAt(untouched.getId())).isNull();
        }
    }



    @Test
    void restoresPreviousCooldownWhenCallerTransactionRollsBack() {
        AppUser owner = persistUser(true);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Rollback");
        AlertRule rule = persistRule(sensor);
        Instant previousTrigger = RECORDED_AT.minusSeconds(600);
        transactions.executeWithoutResult(status ->
                alertRuleRepository.findById(rule.getId()).orElseThrow().markTriggered(previousTrigger));
        IllegalStateException failure = new IllegalStateException("Abort evaluation transaction");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            evaluateInCurrentTransaction(sensor.getId(),RECORDED_AT);
            entityManager.flush();
            throw failure;
        })).isSameAs(failure);

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(previousTrigger);

        events.clear();
        evaluate(sensor.getId(),RECORDED_AT);

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(RECORDED_AT);
        assertThat(events.snapshot()).hasSize(1);
        assertThat(events.snapshot().getFirst().context().recordedAt()).isEqualTo(RECORDED_AT);
    }



    @Test
    void publishedSnapshotRemainsUsableAfterTransactionsAndEntityChanges() {
        AppUser owner = persistUser(true);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Original");
        AlertRule rule = persistRule(sensor);

        evaluate(sensor.getId(),RECORDED_AT);

        assertThat(events.snapshot()).hasSize(1);
        AlertTriggeredEvent published = events.snapshot().getFirst();
        AlertTriggeredEvent.RecipientSnapshot expectedRecipient = new AlertTriggeredEvent.RecipientSnapshot(
                owner.getEmail(),PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,ZoneId.of("America/New_York"));
        AlertTriggeredEvent.SensorSnapshot expectedSensor = new AlertTriggeredEvent.SensorSnapshot(
                sensor.getId(),SensorType.TEMPERATURE,"Original","Window","Istanbul","Kadikoy",ZoneId.of("Europe/Istanbul"));
        AlertTriggeredEvent expected = new AlertTriggeredEvent(
                new AlertTriggeredEvent.Context(rule.getId(),expectedRecipient,expectedSensor,RECORDED_AT,COOLDOWN_MINUTES),
                new AlertTriggeredEvent.NumericThresholdTrigger(ComparisonOperator.ABOVE,25.0,20.0,MeasurementUnit.C));

        transactions.executeWithoutResult(status -> {
            AppUser managedOwner = appUserRepository.findById(owner.getId()).orElseThrow();
            managedOwner.updatePreferences(PreferredLanguage.ENGLISH,TemperatureUnit.KELVIN,"Asia/Tokyo",TEST_INSTANT);
            Sensor managedSensor = sensorRepository.findById(sensor.getId()).orElseThrow();
            managedSensor.updateDetails("Renamed","Ankara","Cankaya","Door","UTC",TEST_INSTANT);
            alertRuleRepository.findById(rule.getId()).orElseThrow().disable();
        });

        assertThat(sensorRepository.findById(sensor.getId()).orElseThrow().getName()).isEqualTo("Renamed");
        assertThat(appUserRepository.findById(owner.getId()).orElseThrow().getPreferredTemperatureUnit()).isEqualTo(TemperatureUnit.KELVIN);
        assertThat(published).isEqualTo(expected);
    }



    @Test
    void serializesConcurrentEvaluationAndPublishesOnlyOneAlertWithinCooldown() throws Exception {
        AppUser owner = persistUser(true);
        Sensor sensor = persistSensor(owner,SensorType.TEMPERATURE,"Concurrent");
        AlertRule rule = persistRule(sensor);
        executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Integer> firstHolding = new CompletableFuture<>();
        CompletableFuture<Integer> secondStarted = new CompletableFuture<>();

        try {
            Future<?> first = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='20s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        evaluateInCurrentTransaction(sensor.getId(),RECORDED_AT);
                        entityManager.flush();
                        firstHolding.complete(backendPid);
                        awaitRelease(releaseFirst);
                    });
                } catch (RuntimeException | Error failure) {
                    firstHolding.completeExceptionally(failure);
                    throw failure;
                }
            });

            int holderPid = firstHolding.get(10,TimeUnit.SECONDS);

            Future<?> second = executor.submit(() -> {
                try {
                    transactions.executeWithoutResult(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='20s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        secondStarted.complete(backendPid);
                        evaluateInCurrentTransaction(sensor.getId(),RECORDED_AT);
                    });
                } catch (RuntimeException | Error failure) {
                    secondStarted.completeExceptionally(failure);
                    throw failure;
                }
            });

            int waiterPid = secondStarted.get(10,TimeUnit.SECONDS);
            assertThat(waiterPid).isNotEqualTo(holderPid);

            await().pollInterval(Duration.ofMillis(25)).atMost(Duration.ofSeconds(10)).until(() ->
                    Boolean.TRUE.equals(jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",Boolean.class,holderPid,waiterPid)));

            releaseFirst.countDown();
            first.get(20,TimeUnit.SECONDS);
            second.get(20,TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
        }

        assertThat(lastTriggeredAt(rule.getId())).isEqualTo(RECORDED_AT);
        assertThat(events.snapshot()).hasSize(1);
        assertThat(events.snapshot().getFirst().context().alertRuleId()).isEqualTo(rule.getId().longValue());
        assertThat(events.snapshot().getFirst().context().recordedAt()).isEqualTo(RECORDED_AT);
    }



    private AppUser persistUser(boolean verified) {
        AppUser user = new AppUser("alert-evaluation-" + UUID.randomUUID() + "@example.com","test-password-hash",
                PreferredLanguage.TURKISH,TemperatureUnit.FAHRENHEIT,"America/New_York",CREATED_AT);
        if (verified) {
            user.verifyEmail(CREATED_AT.plusSeconds(60));
        }
        AppUser saved = appUserRepository.saveAndFlush(user);
        fixtureOwnerIds.add(saved.getId());
        return saved;
    }



    private Sensor persistSensor(AppUser owner,SensorType type,String name) {
        return sensorRepository.saveAndFlush(new Sensor(owner,type,name,"Istanbul","Kadikoy","Window","Europe/Istanbul",CREATED_AT));
    }



    private AlertRule persistRule(Sensor sensor) {
        AlertRule rule = switch (sensor.getType()) {
            case TEMPERATURE -> AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,20.0,COOLDOWN_MINUTES);
            case HUMIDITY -> AlertRule.numericThreshold(sensor,ComparisonOperator.ABOVE,50.0,COOLDOWN_MINUTES);
            case MOTION -> AlertRule.motionDetected(sensor,COOLDOWN_MINUTES);
        };
        return alertRuleRepository.saveAndFlush(rule);
    }



    private Instant lastTriggeredAt(Long ruleId) {
        return alertRuleRepository.findById(ruleId).orElseThrow().getLastTriggeredAt();
    }



    private void evaluate(Long sensorId,Instant recordedAt) {
        transactions.executeWithoutResult(status -> evaluateInCurrentTransaction(sensorId,recordedAt));
    }



    private void evaluateInCurrentTransaction(Long sensorId,Instant recordedAt) {
        Sensor sensor = sensorRepository.findById(sensorId).orElseThrow();
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,25.0,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,70.0,recordedAt);
            case MOTION -> SensorReading.motion(sensor,true,recordedAt);
        };
        service.evaluateReading(reading);
    }



    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(40,TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the first transaction",exception);
        }
    }



    @TestConfiguration(proxyBeanMethods = false)
    static class EventCaptureConfiguration {

        @Bean
        AlertEvents alertEvents() {
            return new AlertEvents();
        }
    }

    static class AlertEvents {

        private final Queue<AlertTriggeredEvent> captured = new ConcurrentLinkedQueue<>();

        @EventListener
        public void onAlert(AlertTriggeredEvent event) {
            captured.add(event);
        }

        List<AlertTriggeredEvent> snapshot() {
            return List.copyOf(captured);
        }

        void clear() {
            captured.clear();
        }
    }
}