package com.enginertugrul.iotsensormonitor.service.user.verification;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.EmailVerificationChallenge;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.repository.EmailVerificationChallengeRepository;
import com.enginertugrul.iotsensormonitor.scheduler.EmailVerificationChallengeCleanupScheduler;
import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationHmac;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationPolicy;
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
class EmailVerificationServiceIT {

    private static final String WRONG_CODE = "99999999";

    @Autowired
    private EmailVerificationService service;

    @Autowired
    private AppUserRepository appUserRepository;

    @Autowired
    private EmailVerificationChallengeRepository challengeRepository;

    @Autowired
    private EmailVerificationChallengeCleanupScheduler cleanupScheduler;

    @Autowired
    private EmailVerificationPolicy policy;

    @Autowired
    private EmailVerificationHmac hmac;

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
    private EmailVerificationCodeGenerator codeGenerator;

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
        clientKey = "verification-client-" + UUID.randomUUID();
        at(TEST_INSTANT);

        AtomicInteger nextCode = new AtomicInteger(10_000_000);
        doAnswer(invocation -> {
            Long userId = invocation.getArgument(0);
            String rawCode = Integer.toString(nextCode.getAndIncrement());
            String codeHash = hmac.digest("email-verification-code",userId + ":" + rawCode);
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
    void commitsInitialChallengeAndPublishesMatchingDelivery() {
        AppUser user = createUser();

        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        EmailVerificationChallenge challenge = readChallenge(user.getId());

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
        assertThat(readUser(user.getId()).isEmailVerified()).isFalse();
        assertThat(service.canDeliverCode(delivery)).isTrue();

        assertThatThrownBy(() -> service.issueInitialCode(user.getId()))
                .isInstanceOf(IllegalStateException.class);

        assertThat(challengeCount(user.getId())).isEqualTo(1);
        assertThat(deliveries(user.getId())).hasSize(1);
    }



    @Test
    void rollsBackInitialChallengeWhenSurroundingTransactionFails() {
        AppUser user = createUser();
        IllegalStateException failure = new IllegalStateException("Rollback initial issuance");

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            service.issueInitialCode(user.getId());
            entityManager.flush();
            assertThat(challengeCount(user.getId())).isEqualTo(1);
            throw failure;
        })).isSameAs(failure);

        assertThat(challengeCount(user.getId())).isZero();
        assertThat(readUser(user.getId()).isEmailVerified()).isFalse();

        service.issueInitialCode(user.getId());

        assertThat(challengeCount(user.getId())).isEqualTo(1);
    }



    @Test
    void enforcesResendBoundaryAndCommitsRotationWithAttemptReset() {
        AppUser user = createUser();
        EmailVerificationCodeDelivery firstDelivery = issueInitialCode(user);
        EmailVerificationChallenge original = readChallenge(user.getId());

        at(TEST_INSTANT.plusSeconds(1));
        assertThat(service.verifyCode(user.getEmail(),WRONG_CODE,clientKey)).isEqualTo(EmailVerificationResult.INVALID);

        at(original.getResendAvailableAt().minusNanos(1000));
        service.requestNewCode(user.getEmail(),clientKey);
        EmailVerificationChallenge beforeBoundary = readChallenge(user.getId());

        assertThat(beforeBoundary.getId()).isEqualTo(original.getId());
        assertThat(beforeBoundary.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(beforeBoundary.getFailedAttempts()).isEqualTo(1);
        assertThat(beforeBoundary.getUpdatedAt()).isEqualTo(TEST_INSTANT.plusSeconds(1));
        assertThat(deliveries(user.getId())).hasSize(1);

        Instant resentAt = original.getResendAvailableAt();
        at(resentAt);
        service.requestNewCode(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ",clientKey);

        EmailVerificationChallenge rotated = readChallenge(user.getId());
        List<EmailVerificationCodeDelivery> issued = deliveries(user.getId());
        assertThat(issued).hasSize(2);
        EmailVerificationCodeDelivery secondDelivery = issued.getLast();

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

        assertThat(service.verifyCode(user.getEmail(),firstDelivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.INVALID);
        assertThat(readChallenge(user.getId()).getFailedAttempts()).isEqualTo(1);
        assertThat(readUser(user.getId()).isEmailVerified()).isFalse();
    }



    @Test
    void commitsFailedAttemptsAndRejectsCorrectCodeAfterLimit() {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        int maximumAttempts = policy.getMaximumFailedAttempts();

        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            Instant attemptedAt = TEST_INSTANT.plusSeconds(attempt);
            at(attemptedAt);

            assertThat(service.verifyCode(user.getEmail(),WRONG_CODE,clientKey)).isEqualTo(EmailVerificationResult.INVALID);

            EmailVerificationChallenge challenge = readChallenge(user.getId());
            assertThat(challenge.getFailedAttempts()).isEqualTo(attempt);
            assertThat(challenge.getUpdatedAt()).isEqualTo(attemptedAt);
        }

        at(TEST_INSTANT.plusSeconds(maximumAttempts + 1L));

        assertThat(service.verifyCode(user.getEmail(),delivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.INVALID);

        EmailVerificationChallenge exhausted = readChallenge(user.getId());
        assertThat(exhausted.getFailedAttempts()).isEqualTo(maximumAttempts);
        assertThat(exhausted.getUpdatedAt()).isEqualTo(TEST_INSTANT.plusSeconds(maximumAttempts));
        assertThat(readUser(user.getId()).isEmailVerified()).isFalse();
        assertThat(service.canDeliverCode(delivery)).isFalse();
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsCorrectCodeAtOrAfterExpiryWithoutIncrementingAttempts(long secondsAfterExpiry) {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        at(delivery.expiresAt().plusSeconds(secondsAfterExpiry));

        assertThat(service.verifyCode(user.getEmail(),delivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.INVALID);

        EmailVerificationChallenge challenge = readChallenge(user.getId());
        assertThat(challenge.getFailedAttempts()).isZero();
        assertThat(challenge.getUpdatedAt()).isEqualTo(TEST_INSTANT);
        assertThat(readUser(user.getId()).isEmailVerified()).isFalse();
        assertThat(service.canDeliverCode(delivery)).isFalse();
    }



    @Test
    void commitsVerificationImmediatelyBeforeExpiryAndPreventsReuse() {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        Instant verifiedAt = delivery.expiresAt().minusNanos(1000);
        at(verifiedAt);

        EmailVerificationResult result = service.verifyCode(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ",delivery.rawCode(),clientKey);

        assertThat(result).isEqualTo(EmailVerificationResult.VERIFIED);
        AppUser verified = readUser(user.getId());
        assertThat(verified.getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(verified.getUpdatedAt()).isEqualTo(verifiedAt);
        assertThat(verified.getPasswordHash()).isEqualTo(user.getPasswordHash());
        assertThat(challengeCount(user.getId())).isZero();
        assertThat(service.canDeliverCode(delivery)).isFalse();

        at(delivery.expiresAt());

        assertThat(service.verifyCode(user.getEmail(),delivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.INVALID);
        assertThat(readUser(user.getId()).getEmailVerifiedAt()).isEqualTo(verifiedAt);
        assertThat(challengeCount(user.getId())).isZero();
    }



    @ParameterizedTest
    @CsvSource({"false,false","false,true","true,false","true,true"})
    void rejectsIneligibleUsersAndRemovesOnlyVerifiedUsersStaleChallenges(boolean verified,boolean resend) {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);

        transactions.executeWithoutResult(status -> {
            AppUser managedUser = appUserRepository.findById(user.getId()).orElseThrow();
            if (verified) {
                managedUser.verifyEmail(TEST_INSTANT.plusSeconds(1));
            } else {
                managedUser.disable(TEST_INSTANT.plusSeconds(1));
            }
        });
        at(TEST_INSTANT.plus(policy.getResendCooldown()));

        if (resend) {
            service.requestNewCode(user.getEmail(),clientKey);
        } else {
            assertThat(service.verifyCode(user.getEmail(),delivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.INVALID);
        }

        assertThat(challengeCount(user.getId())).isEqualTo(verified ? 0L : 1L);
        assertThat(deliveries(user.getId())).hasSize(1);
        assertThat(service.canDeliverCode(delivery)).isFalse();
        assertThat(readUser(user.getId()).isEmailVerified()).isEqualTo(verified);

        if (verified) {
            assertThat(readUser(user.getId()).getEmailVerifiedAt()).isEqualTo(TEST_INSTANT.plusSeconds(1));
        } else {
            assertThat(readUser(user.getId()).isEnabled()).isFalse();
            assertThat(readChallenge(user.getId()).getFailedAttempts()).isZero();
        }
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rollsBackVerificationOrFailedAttemptWhenSurroundingTransactionFails(boolean validCode) {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        EmailVerificationChallenge original = readChallenge(user.getId());
        String submittedCode = validCode ? delivery.rawCode() : WRONG_CODE;
        EmailVerificationResult expected = validCode ? EmailVerificationResult.VERIFIED : EmailVerificationResult.INVALID;
        IllegalStateException failure = new IllegalStateException("Rollback verification attempt");
        at(TEST_INSTANT.plusSeconds(10));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            assertThat(service.verifyCode(user.getEmail(),submittedCode,clientKey)).isEqualTo(expected);
            entityManager.flush();

            if (validCode) {
                assertThat(challengeCount(user.getId())).isZero();
                assertThat(appUserRepository.findById(user.getId()).orElseThrow().isEmailVerified()).isTrue();
            } else {
                Integer attempts = jdbcTemplate.queryForObject("SELECT failed_attempts FROM email_verification_challenges WHERE user_id=?",Integer.class,user.getId());
                assertThat(attempts).isEqualTo(1);
            }
            throw failure;
        })).isSameAs(failure);

        AppUser unchangedUser = readUser(user.getId());
        EmailVerificationChallenge unchangedChallenge = readChallenge(user.getId());
        assertThat(unchangedUser.isEmailVerified()).isFalse();
        assertThat(unchangedUser.getUpdatedAt()).isEqualTo(user.getUpdatedAt());
        assertThat(unchangedChallenge.getId()).isEqualTo(original.getId());
        assertThat(unchangedChallenge.getCodeHash()).isEqualTo(original.getCodeHash());
        assertThat(unchangedChallenge.getFailedAttempts()).isZero();
        assertThat(unchangedChallenge.getUpdatedAt()).isEqualTo(original.getUpdatedAt());

        assertThat(service.verifyCode(user.getEmail(),delivery.rawCode(),clientKey)).isEqualTo(EmailVerificationResult.VERIFIED);
        assertThat(challengeCount(user.getId())).isZero();
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void serializesConcurrentSubmissionsWithoutDoubleConsumptionOrLostAttempts(boolean validCode) throws Exception {
        AppUser user = createUser();
        EmailVerificationCodeDelivery delivery = issueInitialCode(user);
        String submittedCode = validCode ? delivery.rawCode() : WRONG_CODE;
        EmailVerificationResult expectedFirst = validCode ? EmailVerificationResult.VERIFIED : EmailVerificationResult.INVALID;
        at(TEST_INSTANT.plusSeconds(10));

        executor = Executors.newFixedThreadPool(2);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Integer> firstHolding = new CompletableFuture<>();
        CompletableFuture<Integer> secondStarted = new CompletableFuture<>();

        try {
            Future<EmailVerificationResult> first = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='15s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        EmailVerificationResult result = service.verifyCode(user.getEmail(),submittedCode,clientKey + "-first");
                        assertThat(result).isEqualTo(expectedFirst);
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

            Future<EmailVerificationResult> second = executor.submit(() -> {
                try {
                    return transactions.execute(status -> {
                        jdbcTemplate.execute("SET LOCAL lock_timeout='15s'");
                        Integer backendPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()",Integer.class);
                        secondStarted.complete(backendPid);
                        return service.verifyCode(user.getEmail(),submittedCode,clientKey + "-second");
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

            assertThat(first.get(15,TimeUnit.SECONDS)).isEqualTo(expectedFirst);
            assertThat(second.get(15,TimeUnit.SECONDS)).isEqualTo(EmailVerificationResult.INVALID);
        } finally {
            releaseFirst.countDown();
        }

        if (validCode) {
            assertThat(readUser(user.getId()).getEmailVerifiedAt()).isEqualTo(TEST_INSTANT.plusSeconds(10));
            assertThat(challengeCount(user.getId())).isZero();
        } else {
            assertThat(readUser(user.getId()).isEmailVerified()).isFalse();
            assertThat(readChallenge(user.getId()).getFailedAttempts()).isEqualTo(2);
        }
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
        assertThat(appUserRepository.findById(expiredUser.getId())).isPresent();
        assertThat(appUserRepository.findById(boundaryUser.getId())).isPresent();
        assertThat(appUserRepository.findById(futureUser.getId())).isPresent();

        cleanupScheduler.purgeExpiredChallenges();

        assertThat(challengeCount(futureUser.getId())).isEqualTo(1);
    }



    private void at(Instant instant) {
        doReturn(instant).when(clock).instant();
    }



    private AppUser createUser() {
        String email = "verification-" + UUID.randomUUID() + "@example.com";
        fixtureEmails.add(email);
        AppUser user = new AppUser(email,"test-password-hash",PreferredLanguage.TURKISH,TemperatureUnit.CELSIUS,"UTC",TEST_INSTANT.minusSeconds(3600));
        return appUserRepository.saveAndFlush(user);
    }



    private EmailVerificationCodeDelivery issueInitialCode(AppUser user) {
        service.issueInitialCode(user.getId());
        List<EmailVerificationCodeDelivery> issued = deliveries(user.getId());
        assertThat(issued).hasSize(1);
        return issued.getFirst();
    }



    private List<EmailVerificationCodeDelivery> deliveries(Long userId) {
        return applicationEvents.stream(EmailVerificationCodeDelivery.class)
                .filter(delivery -> delivery.userId().equals(userId))
                .toList();
    }



    private AppUser readUser(Long userId) {
        return appUserRepository.findById(userId).orElseThrow();
    }



    private EmailVerificationChallenge readChallenge(Long userId) {
        return transactions.execute(status -> challengeRepository.findByUserIdForUpdate(userId).orElseThrow());
    }



    private long challengeCount(Long userId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM email_verification_challenges WHERE user_id=?",Long.class,userId);
    }



    private void saveCleanupChallenge(AppUser user,Instant expiresAt) {
        Instant issuedAt = TEST_INSTANT.minusSeconds(600);
        challengeRepository.saveAndFlush(new EmailVerificationChallenge(user,"cleanup-code-hash",issuedAt,expiresAt,issuedAt.plusSeconds(60)));
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