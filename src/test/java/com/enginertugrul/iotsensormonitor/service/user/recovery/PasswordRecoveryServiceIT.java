package com.enginertugrul.iotsensormonitor.service.user.recovery;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PasswordResetChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.PasswordResetChallengeRepository;
import com.enginertugrul.iotsensormonitor.scheduler.PasswordResetChallengeCleanupScheduler;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryHmac;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryPolicy;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangedEvent;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.enginertugrul.iotsensormonitor.testsupport.TestRuntimeConfiguration.TEST_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;



@SpringBootTest(properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({PostgresTestConfiguration.class,TestRuntimeConfiguration.class})
@RecordApplicationEvents
@Execution(ExecutionMode.SAME_THREAD)
class PasswordRecoveryServiceIT {

    private static final String OLD_PASSWORD = "original-password";
    private static final String NEW_PASSWORD = "replacement-password";
    private static final String OTHER_PASSWORD = "another-replacement-password";
    private static final String WRONG_CODE = "99999999";

    @Autowired
    private PasswordRecoveryService service;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private PasswordResetChallengeRepository challengeRepository;

    @Autowired
    private PasswordResetChallengeCleanupScheduler cleanupScheduler;

    @Autowired
    private PasswordRecoveryPolicy policy;

    @Autowired
    private PasswordRecoveryHmac hmac;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationEvents applicationEvents;

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoSpyBean(name = "testClock")
    private Clock clock;

    @MockitoSpyBean
    private PasswordRecoveryCodeGenerator codeGenerator;

    private final List<String> fixtureEmails = new ArrayList<>();
    private TransactionTemplate transactions;
    private ExecutorService executor;
    private String clientKey;



    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
        clientKey = "recovery-client-" + UUID.randomUUID();
        at(TEST_INSTANT);

        AtomicInteger nextCode = new AtomicInteger(10_000_000);
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            String rawCode = Integer.toString(nextCode.getAndIncrement());
            String codeHash = hmac.digest("password-reset-code",userId + ":" + rawCode);
            return new GeneratedOneTimeCode(rawCode,codeHash);
        }).when(codeGenerator).generate(anyLong());
    }



    @AfterEach
    void tearDown() throws InterruptedException {
        if (executor != null) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20,TimeUnit.SECONDS))
                    .as("Concurrent transactions must finish before fixture cleanup")
                    .isTrue();
        }

        for (String email : fixtureEmails) {
            jdbcTemplate.update("DELETE FROM app_users WHERE email=?",email);
        }
    }



    @Test
    void commitsInitialChallengeAndPublishesMatchingDeliveryWithoutChangingPassword() {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        PasswordResetChallenge challenge = readChallenge(user.getId());

        assertThat(challenge.getId()).isPositive();
        assertThat(challengeCount(user.getId())).isEqualTo(1);
        assertThat(challenge.getCodeHash()).isNotEqualTo(delivery.rawCode());
        assertThat(codeGenerator.matches(user.getId(),delivery.rawCode(),challenge.getCodeHash())).isTrue();
        assertThat(challenge.getIssuedAt()).isEqualTo(TEST_INSTANT);
        assertThat(challenge.getExpiresAt()).isEqualTo(TEST_INSTANT.plus(policy.getCodeLifetime()));
        assertThat(challenge.getResendAvailableAt()).isEqualTo(TEST_INSTANT.plus(policy.getResendCooldown()));
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getCreatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(challenge.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(delivery.recipientEmail()).isEqualTo(user.getEmail());
        assertThat(delivery.preferredLanguage()).isEqualTo(PreferredLanguage.TURKISH);
        assertThat(delivery.expiresAt()).isEqualTo(challenge.getExpiresAt());
        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(readUser(user.getId()).getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(service.canDeliverCode(delivery)).isTrue();
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void returnsSameInvalidResultForUnknownUserAndUserWithoutChallenge() {
        AppUser user = createUser();
        String unknownEmail = "unknown-" + UUID.randomUUID() + "@example.com";

        service.requestResetCode(unknownEmail,clientKey);

        assertThat(service.resetPassword(unknownEmail,WRONG_CODE,NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);
        assertThat(service.resetPassword(user.getEmail(),WRONG_CODE,NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(applicationEvents.stream(PasswordRecoveryCodeDelivery.class).count()).isZero();
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void enforcesResendBoundaryAndCommitsRotationWithAttemptReset() {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery firstDelivery = issueCode(user);
        PasswordResetChallenge original = readChallenge(user.getId());

        at(TEST_INSTANT.plusSeconds(1));
        assertThat(service.resetPassword(user.getEmail(),WRONG_CODE,NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);

        at(original.getResendAvailableAt().minusNanos(1000));
        service.requestResetCode(user.getEmail(),clientKey);
        PasswordResetChallenge beforeBoundary = readChallenge(user.getId());

        assertThat(beforeBoundary.getId()).isEqualTo(original.getId());
        assertThat(beforeBoundary.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(beforeBoundary.getFailedAttempts()).isEqualTo(1);
        assertThat(beforeBoundary.getUpdatedAt()).isEqualTo(TEST_INSTANT.plusSeconds(1));
        assertThat(deliveries(user.getId())).hasSize(1);

        Instant resentAt = original.getResendAvailableAt();
        at(resentAt);
        service.requestResetCode(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ",clientKey);

        PasswordResetChallenge rotated = readChallenge(user.getId());
        List<PasswordRecoveryCodeDelivery> issued = deliveries(user.getId());
        assertThat(issued).hasSize(2);
        PasswordRecoveryCodeDelivery secondDelivery = issued.getLast();

        assertThat(challengeCount(user.getId())).isEqualTo(1);
        assertThat(rotated.getId()).isEqualTo(original.getId());
        assertThat(rotated.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(rotated.getCodeHash()).isNotEqualTo(original.getCodeHash());
        assertThat(rotated.getIssuedAt()).isEqualTo(resentAt);
        assertThat(rotated.getUpdatedAt()).isEqualTo(resentAt);
        assertThat(rotated.getExpiresAt()).isEqualTo(resentAt.plus(policy.getCodeLifetime()));
        assertThat(rotated.getResendAvailableAt()).isEqualTo(resentAt.plus(policy.getResendCooldown()));
        assertThat(rotated.getFailedAttempts()).isZero();
        assertThat(codeGenerator.matches(user.getId(),secondDelivery.rawCode(),rotated.getCodeHash())).isTrue();
        assertThat(service.canDeliverCode(firstDelivery)).isFalse();
        assertThat(service.canDeliverCode(secondDelivery)).isTrue();
        assertThat(readChallenge(user.getId()).getFailedAttempts()).isZero();

        assertThat(service.resetPassword(user.getEmail(),firstDelivery.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);
        assertThat(readChallenge(user.getId()).getFailedAttempts()).isEqualTo(1);
        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());

        assertThat(service.resetPassword(user.getEmail(),secondDelivery.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
        assertThat(passwordEncoder.matches(NEW_PASSWORD,readUser(user.getId()).getPasswordHash())).isTrue();
        assertThat(challengeCount(user.getId())).isZero();
    }



    @Test
    void commitsFailedAttemptsAndRejectsCorrectCodeAfterLimit() {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        int maximumAttempts = policy.getMaximumFailedAttempts();

        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            Instant attemptedAt = TEST_INSTANT.plusSeconds(attempt);
            at(attemptedAt);

            assertThat(service.resetPassword(user.getEmail(),WRONG_CODE,NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);

            PasswordResetChallenge challenge = readChallenge(user.getId());
            assertThat(challenge.getFailedAttempts()).isEqualTo(attempt);
            assertThat(challenge.getUpdatedAt()).isEqualTo(attemptedAt);
        }

        at(TEST_INSTANT.plusSeconds(maximumAttempts + 1L));

        assertThat(service.resetPassword(user.getEmail(),delivery.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);

        PasswordResetChallenge exhausted = readChallenge(user.getId());
        assertThat(exhausted.getFailedAttempts()).isEqualTo(maximumAttempts);
        assertThat(exhausted.getUpdatedAt()).isEqualTo(TEST_INSTANT.plusSeconds(maximumAttempts));
        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsCorrectCodeAtOrAfterExpiryWithoutChangingPasswordOrAttempts(long secondsAfterExpiry) {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        at(delivery.expiresAt().plusSeconds(secondsAfterExpiry));

        assertThat(service.resetPassword(user.getEmail(),delivery.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);

        PasswordResetChallenge challenge = readChallenge(user.getId());
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void commitsPasswordReplacementImmediatelyBeforeExpiryAndPreventsCodeReuse() {
        AppUser user = createUser();
        AppUser otherUser = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        PasswordRecoveryCodeDelivery otherDelivery = issueCode(otherUser);
        Instant resetAt = delivery.expiresAt().minusNanos(1000);
        at(resetAt);

        PasswordRecoveryResult result = service.resetPassword(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ",delivery.rawCode(),NEW_PASSWORD,clientKey);

        assertThat(result).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
        AppUser changed = readUser(user.getId());
        assertThat(changed.getPasswordHash()).isNotEqualTo(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(passwordEncoder.matches(NEW_PASSWORD,changed.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(OLD_PASSWORD,changed.getPasswordHash())).isFalse();
        assertThat(changed.getUpdatedAt()).isEqualTo(resetAt);
        assertThat(changed.getEmailVerifiedAt()).isEqualTo(user.getEmailVerifiedAt());
        assertThat(changed.getPreferredLanguage()).isEqualTo(user.getPreferredLanguage());
        assertThat(changed.getPreferredTemperatureUnit()).isEqualTo(user.getPreferredTemperatureUnit());
        assertThat(changed.getPreferredTimezone()).isEqualTo(user.getPreferredTimezone());
        assertThat(changed.isEnabled()).isTrue();
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(passwordChanges(user.getId())).containsExactly(new PasswordChangedEvent(user.getId()));

        assertThat(service.resetPassword(user.getEmail(),delivery.rawCode(),OTHER_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);

        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(changed.getPasswordHash());
        assertThat(readUser(user.getId()).getUpdatedAt()).isEqualTo(resetAt);
        assertThat(passwordChanges(user.getId())).hasSize(1);
        assertThat(readUser(otherUser.getId()).getPasswordHash()).isEqualTo(otherUser.getPasswordHash());
        assertThat(challengeCount(otherUser.getId())).isEqualTo(1);
        assertThat(service.canDeliverCode(otherDelivery)).isTrue();
        assertThat(passwordChanges(otherUser.getId())).isEmpty();
    }



    @ParameterizedTest
    @CsvSource({
            "false,true,true",
            "false,true,false",
            "true,false,true",
            "true,false,false",
            "false,false,true",
            "false,false,false"
    })
    void rejectsIneligibleUsersAndCommitsStaleChallengeDeletion(boolean verified,boolean enabled,boolean request) {
        AppUser user = createUser(verified,enabled);
        GeneratedOneTimeCode code = codeGenerator.generate(user.getId());
        Instant expiresAt = TEST_INSTANT.plus(policy.getCodeLifetime());
        challengeRepository.saveAndFlush(new PasswordResetChallenge(user,code.codeHash(),TEST_INSTANT,expiresAt,TEST_INSTANT.plus(policy.getResendCooldown())));
        PasswordRecoveryCodeDelivery delivery = new PasswordRecoveryCodeDelivery(user.getId(),user.getEmail(),user.getPreferredLanguage(),code.rawCode(),expiresAt);

        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(challengeCount(user.getId())).isEqualTo(1);

        if (request) {
            service.requestResetCode(user.getEmail(),clientKey);
        } else {
            assertThat(service.resetPassword(user.getEmail(),code.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.INVALID);
        }

        AppUser unchanged = readUser(user.getId());
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(unchanged.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(unchanged.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(unchanged.isEmailVerified()).isEqualTo(verified);
        assertThat(unchanged.isEnabled()).isEqualTo(enabled);
        assertThat(deliveries(user.getId())).isEmpty();
        assertThat(passwordChanges(user.getId())).isEmpty();
    }



    @Test
    void rejectsPreviouslyIssuedDeliveryAfterUserDeletion() {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);

        assertThat(jdbcTemplate.update("DELETE FROM app_users WHERE id=?",user.getId())).isEqualTo(1);

        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(challengeCount(user.getId())).isZero();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rollsBackInitialIssuanceOrRotationWhenSurroundingTransactionFails(boolean existingChallenge) {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery originalDelivery = existingChallenge ? issueCode(user) : null;
        PasswordResetChallenge original = existingChallenge ? readChallenge(user.getId()) : null;
        Instant requestedAt = TEST_INSTANT.plus(policy.getResendCooldown());
        IllegalStateException failure = new IllegalStateException("Rollback recovery issuance");
        at(requestedAt);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.requestResetCode(user.getEmail(),clientKey);
            entityManager.flush();

            PasswordResetChallenge pending = challengeRepository.findByUserIdForUpdate(user.getId()).orElseThrow();
            assertThat(pending.getIssuedAt()).isEqualTo(requestedAt);
            assertThat(challengeCount(user.getId())).isEqualTo(1);
            if (original != null) {
                assertThat(pending.getCodeHash()).isNotEqualTo(original.getCodeHash());
            }
            throw failure;
        })).isSameAs(failure);

        assertThat(readUser(user.getId()).getPasswordHash()).isEqualTo(user.getPasswordHash());
        if (existingChallenge) {
            PasswordResetChallenge unchanged = readChallenge(user.getId());
            assertThat(unchanged.getId()).isEqualTo(original.getId());
            assertThat(unchanged.getCodeHash()).isEqualTo(original.getCodeHash());
            assertThat(unchanged.getIssuedAt()).isEqualTo(original.getIssuedAt());
            assertThat(unchanged.getExpiresAt()).isEqualTo(original.getExpiresAt());
            assertThat(unchanged.getResendAvailableAt()).isEqualTo(original.getResendAvailableAt());
            assertThat(unchanged.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
            assertThat(service.canDeliverCode(originalDelivery)).isTrue();
        } else {
            assertThat(challengeCount(user.getId())).isZero();
        }
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rollsBackPasswordReplacementOrFailedAttemptWhenSurroundingTransactionFails(boolean validCode) {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        PasswordResetChallenge original = readChallenge(user.getId());
        String submittedCode = validCode ? delivery.rawCode() : WRONG_CODE;
        PasswordRecoveryResult expected = validCode ? PasswordRecoveryResult.PASSWORD_RESET : PasswordRecoveryResult.INVALID;
        IllegalStateException failure = new IllegalStateException("Rollback recovery attempt");
        at(TEST_INSTANT.plusSeconds(10));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(service.resetPassword(user.getEmail(),submittedCode,NEW_PASSWORD,clientKey)).isEqualTo(expected);
            entityManager.flush();

            if (validCode) {
                assertThat(challengeCount(user.getId())).isZero();
                String pendingHash = jdbcTemplate.queryForObject("SELECT password_hash FROM app_users WHERE id=?",String.class,user.getId());
                assertThat(passwordEncoder.matches(NEW_PASSWORD,pendingHash)).isTrue();
            } else {
                Integer attempts = jdbcTemplate.queryForObject("SELECT failed_attempts FROM password_reset_challenges WHERE user_id=?",Integer.class,user.getId());
                assertThat(attempts).isEqualTo(1);
            }
            throw failure;
        })).isSameAs(failure);

        AppUser unchangedUser = readUser(user.getId());
        PasswordResetChallenge unchangedChallenge = readChallenge(user.getId());
        assertThat(unchangedUser.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(unchangedUser.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(unchangedChallenge.getId()).isEqualTo(original.getId());
        assertThat(unchangedChallenge.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(unchangedChallenge.getFailedAttempts()).isZero();
        assertThat(unchangedChallenge.getUpdatedAt()).isEqualTo(original.getUpdatedAt());
        assertThat(service.canDeliverCode(delivery)).isTrue();

        assertThat(service.resetPassword(user.getEmail(),delivery.rawCode(),NEW_PASSWORD,clientKey)).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
        assertThat(passwordEncoder.matches(NEW_PASSWORD,readUser(user.getId()).getPasswordHash())).isTrue();
        assertThat(challengeCount(user.getId())).isZero();
    }



    @Test
    void serializesConcurrentResetsSoOnlyOnePasswordReplacementCommits() throws Exception {
        AppUser user = createUser();
        PasswordRecoveryCodeDelivery delivery = issueCode(user);
        at(TEST_INSTANT.plusSeconds(10));

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Integer> firstHolding = new CompletableFuture<>();
        CompletableFuture<Integer> secondStarted = new CompletableFuture<>();

        try {
            Future<PasswordRecoveryResult> first = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='15s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        PasswordRecoveryResult result = service.resetPassword(user.getEmail(),delivery.rawCode(),NEW_PASSWORD,clientKey + "-first");
                        assertThat(result).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
                        entityManager.flush();
                        firstHolding.complete(backendPid);
                        awaitRelease(releaseFirst);
                        return result;
                    });
                } catch (RuntimeException | Error failure) {
                    firstHolding.completeExceptionally(failure);
                    throw failure;
                }
            });

            int holderPid = firstHolding.get(10,TimeUnit.SECONDS);

            Future<PasswordRecoveryResult> second = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='15s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        secondStarted.complete(backendPid);
                        return service.resetPassword(user.getEmail(),delivery.rawCode(),OTHER_PASSWORD,clientKey + "-second");
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

            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(PasswordRecoveryResult.PASSWORD_RESET);
            assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(PasswordRecoveryResult.INVALID);
        } finally {
            releaseFirst.countDown();
        }

        AppUser changed = readUser(user.getId());
        assertThat(passwordEncoder.matches(NEW_PASSWORD,changed.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(OTHER_PASSWORD,changed.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(OLD_PASSWORD,changed.getPasswordHash())).isFalse();
        assertThat(changed.getUpdatedAt()).isEqualTo(TEST_INSTANT.plusSeconds(10));
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(service.canDeliverCode(delivery)).isFalse();
    }



    @Test
    void cleanupSchedulerDeletesExpiredAndExactlyExpiringChallengesButPreservesFutureChallengeAndUsers() {
        AppUser expiredUser = createUser();
        AppUser boundaryUser = createUser();
        AppUser futureUser = createUser();
        saveCleanupChallenge(expiredUser,TEST_INSTANT.minusNanos(1000));
        saveCleanupChallenge(boundaryUser,TEST_INSTANT);
        saveCleanupChallenge(futureUser,TEST_INSTANT.plusNanos(1000));

        cleanupScheduler.purgeExpiredChallenges();

        assertThat(challengeCount(expiredUser.getId())).isZero();
        assertThat(challengeCount(boundaryUser.getId())).isZero();
        assertThat(challengeCount(futureUser.getId())).isEqualTo(1);
        assertThat(readChallenge(futureUser.getId()).getExpiresAt()).isEqualTo(TEST_INSTANT.plusNanos(1000));
        assertThat(readUser(expiredUser.getId()).getPasswordHash()).isEqualTo(expiredUser.getPasswordHash());
        assertThat(readUser(boundaryUser.getId()).getPasswordHash()).isEqualTo(boundaryUser.getPasswordHash());
        assertThat(readUser(futureUser.getId()).getPasswordHash()).isEqualTo(futureUser.getPasswordHash());

        cleanupScheduler.purgeExpiredChallenges();

        assertThat(challengeCount(futureUser.getId())).isEqualTo(1);
    }



    private void at(Instant instant) {
        doReturn(instant).when(clock).instant();
    }



    private AppUser createUser() {
        return createUser(true,true);
    }



    private AppUser createUser(boolean verified,boolean enabled) {
        String email = "recovery-" + UUID.randomUUID() + "@example.com";
        fixtureEmails.add(email);
        AppUser user = new AppUser(email,passwordEncoder.encode(OLD_PASSWORD),PreferredLanguage.TURKISH,TemperatureUnit.CELSIUS,"UTC",TEST_INSTANT.minusSeconds(3600));
        if (verified) {
            user.verifyEmail(TEST_INSTANT.minusSeconds(1800));
        }
        if (!enabled) {
            user.disable(TEST_INSTANT.minusSeconds(600));
        }
        return appUserRepository.saveAndFlush(user);
    }



    private PasswordRecoveryCodeDelivery issueCode(AppUser user) {
        service.requestResetCode(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ",clientKey);
        List<PasswordRecoveryCodeDelivery> issued = deliveries(user.getId());
        assertThat(issued).hasSize(1);
        return issued.getFirst();
    }



    private List<PasswordRecoveryCodeDelivery> deliveries(Long userId) {
        return applicationEvents.stream(PasswordRecoveryCodeDelivery.class)
                .filter(delivery -> delivery.userId().equals(userId))
                .toList();
    }



    private List<PasswordChangedEvent> passwordChanges(Long userId) {
        return applicationEvents.stream(PasswordChangedEvent.class)
                .filter(event -> event.userId().equals(userId))
                .toList();
    }



    private AppUser readUser(Long userId) {
        return appUserRepository.findById(userId).orElseThrow();
    }

    private PasswordResetChallenge readChallenge(Long userId) {
        return transactions.execute(status -> challengeRepository.findByUserIdForUpdate(userId).orElseThrow());
    }



    private long challengeCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM password_reset_challenges WHERE user_id=?",Long.class,userId);
    }



    private void saveCleanupChallenge(AppUser user,Instant expiresAt) {
        Instant issuedAt = TEST_INSTANT.minusSeconds(600);
        challengeRepository.saveAndFlush(new PasswordResetChallenge(user,"cleanup-code-hash",issuedAt,expiresAt,issuedAt.plusSeconds(60)));
    }



    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30,TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release the first transaction");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while holding the first transaction",exception);
        }
    }
}