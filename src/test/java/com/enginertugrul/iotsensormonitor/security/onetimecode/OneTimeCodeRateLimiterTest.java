package com.enginertugrul.iotsensormonitor.security.onetimecode;

import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryHmac;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryPolicy;
import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryRateLimiter;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationHmac;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationPolicy;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;



class OneTimeCodeRateLimiterTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Duration ISSUE_WINDOW = Duration.ofSeconds(60);
    private static final Duration SUBMISSION_WINDOW = Duration.ofSeconds(30);
    private static final String PURPOSE_PREFIX = "test-rate-limit:";
    private static final String SUBMISSION_SCOPE = "submission-client";
    private static final String ENCODED_SECRET = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    private final HmacDigest hmac = new HmacSha256(ENCODED_SECRET,"Test HMAC secret");



    @Test
    void requiresAnHmacDigestAndPolicy() {
        OneTimeCodePolicy policy = policy(1,1,1,10);

        assertThatNullPointerException()
                .isThrownBy(() -> new OneTimeCodeRateLimiter(null,policy,PURPOSE_PREFIX,SUBMISSION_SCOPE))
                .withMessage("hmacDigest must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new OneTimeCodeRateLimiter(hmac,null,PURPOSE_PREFIX,SUBMISSION_SCOPE))
                .withMessage("policy must not be null");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void requiresNonBlankPurposePrefixAndSubmissionScope(String value) {
        OneTimeCodePolicy policy = policy(1,1,1,10);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OneTimeCodeRateLimiter(hmac,policy,value,SUBMISSION_SCOPE))
                .withMessage("rateLimitPurposePrefix must not be blank");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new OneTimeCodeRateLimiter(hmac,policy,PURPOSE_PREFIX,value))
                .withMessage("submissionScope must not be blank");
    }



    @Test
    void rejectsMissingTimestampsWithoutConsumingQuotas() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,10);

        assertThatNullPointerException()
                .isThrownBy(() -> limiter.allowCodeIssue("owner@example.com","client",null))
                .withMessage("requestedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> limiter.allowSubmission("client",null))
                .withMessage("submittedAt must not be null");

        assertThat(limiter.allowCodeIssue("owner@example.com","client",START)).isTrue();
        assertThat(limiter.allowSubmission("client",START)).isTrue();
    }



    @Test
    void limitsCodeIssueByAddressAcrossDifferentClients() {
        OneTimeCodeRateLimiter limiter = limiter(2,10,3,100);

        assertThat(limiter.allowCodeIssue("owner@example.com","client-1",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client-2",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client-3",START)).isFalse();
        assertThat(limiter.allowCodeIssue("other@example.com","client-3",START)).isTrue();
    }



    @Test
    void limitsCodeIssueByClientAcrossDifferentAddresses() {
        OneTimeCodeRateLimiter limiter = limiter(10,2,3,100);

        assertThat(limiter.allowCodeIssue("first@example.com","client",START)).isTrue();
        assertThat(limiter.allowCodeIssue("second@example.com","client",START)).isTrue();
        assertThat(limiter.allowCodeIssue("third@example.com","client",START)).isFalse();
        assertThat(limiter.allowCodeIssue("third@example.com","other-client",START)).isTrue();
    }



    @Test
    void consumesClientQuotaEvenWhenTheAddressRejectsIssuance() {
        OneTimeCodeRateLimiter limiter = limiter(1,2,3,100);

        assertThat(limiter.allowCodeIssue("first@example.com","client-1",START)).isTrue();
        assertThat(limiter.allowCodeIssue("first@example.com","client-2",START)).isFalse();
        assertThat(limiter.allowCodeIssue("second@example.com","client-2",START)).isTrue();
        assertThat(limiter.allowCodeIssue("third@example.com","client-2",START)).isFalse();
    }



    @Test
    void consumesAddressQuotaEvenWhenTheClientRejectsIssuance() {
        OneTimeCodeRateLimiter limiter = limiter(2,1,3,100);

        assertThat(limiter.allowCodeIssue("first@example.com","client-1",START)).isTrue();
        assertThat(limiter.allowCodeIssue("second@example.com","client-1",START)).isFalse();
        assertThat(limiter.allowCodeIssue("second@example.com","client-2",START)).isTrue();
        assertThat(limiter.allowCodeIssue("second@example.com","client-3",START)).isFalse();
    }



    @Test
    void limitsSubmissionsIndependentlyForEachClient() {
        OneTimeCodeRateLimiter limiter = limiter(2,3,3,100);

        assertThat(limiter.allowSubmission("client-1",START)).isTrue();
        assertThat(limiter.allowSubmission("client-1",START)).isTrue();
        assertThat(limiter.allowSubmission("client-1",START)).isTrue();
        assertThat(limiter.allowSubmission("client-1",START)).isFalse();
        assertThat(limiter.allowSubmission("client-2",START)).isTrue();
    }



    @Test
    void separatesAddressIssueClientAndSubmissionScopesForIdenticalKeys() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,100);

        assertThat(limiter.allowCodeIssue("same-key","same-key",START)).isTrue();
        assertThat(limiter.allowCodeIssue("same-key","same-key",START)).isFalse();
        assertThat(limiter.allowSubmission("same-key",START)).isTrue();
        assertThat(limiter.allowSubmission("same-key",START)).isFalse();
    }



    @Test
    void trimsAddressAndClientKeysBeforeLookingUpCounters() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,100);

        assertThat(limiter.allowCodeIssue(" \towner@example.com\r\n"," \tclient\r\n",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","other-client",START)).isFalse();
        assertThat(limiter.allowCodeIssue("other@example.com","client",START)).isFalse();

        assertThat(limiter.allowSubmission(" \tclient\r\n",START)).isTrue();
        assertThat(limiter.allowSubmission("client",START)).isFalse();
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void groupsMissingAndBlankKeysIntoOneUnknownCounterPerScope(String unknownKey) {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,100);

        assertThat(limiter.allowCodeIssue(null,"client-1",START)).isTrue();
        assertThat(limiter.allowCodeIssue(unknownKey,"client-2",START)).isFalse();

        assertThat(limiter.allowCodeIssue("first@example.com",null,START)).isTrue();
        assertThat(limiter.allowCodeIssue("second@example.com",unknownKey,START)).isFalse();

        assertThat(limiter.allowSubmission(null,START)).isTrue();
        assertThat(limiter.allowSubmission(unknownKey,START)).isFalse();
    }



    @Test
    void resetsIssueQuotasAtExactExpiryWithoutSlidingTheWindow() {
        OneTimeCodeRateLimiter limiter = limiter(2,2,3,100);
        Instant expiresAt = START.plus(ISSUE_WINDOW);

        assertThat(limiter.allowCodeIssue("owner@example.com","client",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",START.plusSeconds(30))).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",expiresAt.minusNanos(1))).isFalse();

        assertThat(limiter.allowCodeIssue("owner@example.com","client",expiresAt)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",expiresAt)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",expiresAt)).isFalse();
    }



    @Test
    void resetsSubmissionQuotaAtExactExpiryWithoutSlidingTheWindow() {
        OneTimeCodeRateLimiter limiter = limiter(2,3,2,100);
        Instant expiresAt = START.plus(SUBMISSION_WINDOW);

        assertThat(limiter.allowSubmission("client",START)).isTrue();
        assertThat(limiter.allowSubmission("client",START.plusSeconds(15))).isTrue();
        assertThat(limiter.allowSubmission("client",expiresAt.minusNanos(1))).isFalse();

        assertThat(limiter.allowSubmission("client",expiresAt)).isTrue();
        assertThat(limiter.allowSubmission("client",expiresAt)).isTrue();
        assertThat(limiter.allowSubmission("client",expiresAt)).isFalse();
    }



    @Test
    void usesIndependentIssueAndSubmissionWindowDurations() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,100);

        assertThat(limiter.allowCodeIssue("owner@example.com","client",START)).isTrue();
        assertThat(limiter.allowSubmission("client",START)).isTrue();

        Instant submissionExpiry = START.plus(SUBMISSION_WINDOW);
        assertThat(limiter.allowSubmission("client",submissionExpiry)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",submissionExpiry)).isFalse();

        Instant issueExpiry = START.plus(ISSUE_WINDOW);
        assertThat(limiter.allowCodeIssue("owner@example.com","client",issueExpiry)).isTrue();
        assertThat(limiter.allowSubmission("client",issueExpiry)).isTrue();
    }



    @Test
    void evictsTheLeastRecentlyUsedCounterIncludingAccessByRejectedRequests() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,2);

        assertThat(limiter.allowSubmission("client-a",START)).isTrue();
        assertThat(limiter.allowSubmission("client-b",START)).isTrue();
        assertThat(limiter.allowSubmission("client-a",START)).isFalse();

        assertThat(limiter.allowSubmission("client-c",START)).isTrue();

        assertThat(limiter.allowSubmission("client-a",START)).isFalse();
        assertThat(limiter.allowSubmission("client-b",START)).isTrue();
    }



    @Test
    void purgesExpiredCountersBeforeEvictingUnexpiredCounters() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,2);

        assertThat(limiter.allowSubmission("client-a",START)).isTrue();
        assertThat(limiter.allowSubmission("client-b",START.plusSeconds(10))).isTrue();
        assertThat(limiter.allowSubmission("client-a",START.plusSeconds(20))).isFalse();

        Instant firstExpiry = START.plus(SUBMISSION_WINDOW);

        assertThat(limiter.allowSubmission("client-c",firstExpiry)).isTrue();
        assertThat(limiter.allowSubmission("client-b",firstExpiry)).isFalse();
    }



    @Test
    void sharesCounterCapacityAcrossIssueAndSubmissionScopes() {
        OneTimeCodeRateLimiter limiter = limiter(1,1,1,3);

        assertThat(limiter.allowCodeIssue("owner@example.com","issue-client",START)).isTrue();
        assertThat(limiter.allowSubmission("old-submission-client",START)).isTrue();

        assertThat(limiter.allowCodeIssue("owner@example.com","issue-client",START)).isFalse();
        assertThat(limiter.allowSubmission("new-submission-client",START)).isTrue();

        assertThat(limiter.allowCodeIssue("owner@example.com","issue-client",START)).isFalse();
        assertThat(limiter.allowSubmission("old-submission-client",START)).isTrue();
    }



    @ParameterizedTest
    @ValueSource(strings = {"address","client","submission"})
    @Timeout(30)
    void concurrentRequestsCannotExceedTheSharedQuota(String scope) throws Exception {
        int requestCount = 16;
        int maximumAllowed = 5;
        OneTimeCodeRateLimiter limiter = limiter(maximumAllowed,maximumAllowed,maximumAllowed,100);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try {
            for (int index = 0; index < requestCount; index++) {
                int request = index;

                results.add(executor.submit(() -> {
                    ready.countDown();

                    if (!start.await(10,TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Concurrent request start was not released");
                    }

                    return switch (scope) {
                        case "address" -> limiter.allowCodeIssue("shared@example.com","client-" + request,START);
                        case "client" -> limiter.allowCodeIssue("owner-" + request + "@example.com","shared-client",START);
                        case "submission" -> limiter.allowSubmission("shared-client",START);
                        default -> throw new IllegalArgumentException("Unknown request scope: " + scope);
                    };
                }));
            }

            assertThat(ready.await(10,TimeUnit.SECONDS))
                    .as("Every request worker reaches the start gate")
                    .isTrue();

            start.countDown();

            int accepted = 0;

            for (Future<Boolean> result : results) {
                if (result.get(10,TimeUnit.SECONDS)) {
                    accepted++;
                }
            }

            assertThat(accepted).isEqualTo(maximumAllowed);
        } finally {
            start.countDown();
            executor.shutdownNow();

            assertThat(executor.awaitTermination(10,TimeUnit.SECONDS))
                    .as("All request workers terminate")
                    .isTrue();
        }
    }



    @Test
    void verificationWrapperUsesItsConfiguredLimitsWindowsAndHmacScopes() {
        EmailVerificationHmac verificationHmac = spy(new EmailVerificationHmac(ENCODED_SECRET));
        EmailVerificationPolicy policy = new EmailVerificationPolicy(Duration.ofMinutes(15),Duration.ofMinutes(1),5,ISSUE_WINDOW,1,1,SUBMISSION_WINDOW,2,100);
        EmailVerificationRateLimiter limiter = new EmailVerificationRateLimiter(verificationHmac,policy);

        assertThat(limiter.allowCodeIssue(" owner@example.com "," client ",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",START)).isFalse();

        assertThat(limiter.allowVerification(" client ",START)).isTrue();
        assertThat(limiter.allowVerification("client",START)).isTrue();
        assertThat(limiter.allowVerification("client",START)).isFalse();

        Instant submissionExpiry = START.plus(SUBMISSION_WINDOW);
        assertThat(limiter.allowVerification("client",submissionExpiry)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",submissionExpiry)).isFalse();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",START.plus(ISSUE_WINDOW))).isTrue();

        verify(verificationHmac,atLeastOnce()).digest("email-verification-rate-limit:issue-address","owner@example.com");
        verify(verificationHmac,atLeastOnce()).digest("email-verification-rate-limit:issue-client","client");
        verify(verificationHmac,atLeastOnce()).digest("email-verification-rate-limit:verification-client","client");
    }



    @Test
    void recoveryWrapperUsesItsConfiguredLimitsWindowsAndHmacScopes() {
        PasswordRecoveryHmac recoveryHmac = spy(new PasswordRecoveryHmac(ENCODED_SECRET));
        PasswordRecoveryPolicy policy = new PasswordRecoveryPolicy(Duration.ofMinutes(15),Duration.ofMinutes(1),5,ISSUE_WINDOW,1,1,SUBMISSION_WINDOW,2,100);
        PasswordRecoveryRateLimiter limiter = new PasswordRecoveryRateLimiter(recoveryHmac,policy);

        assertThat(limiter.allowCodeIssue(" owner@example.com "," client ",START)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",START)).isFalse();

        assertThat(limiter.allowPasswordReset(" client ",START)).isTrue();
        assertThat(limiter.allowPasswordReset("client",START)).isTrue();
        assertThat(limiter.allowPasswordReset("client",START)).isFalse();

        Instant submissionExpiry = START.plus(SUBMISSION_WINDOW);
        assertThat(limiter.allowPasswordReset("client",submissionExpiry)).isTrue();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",submissionExpiry)).isFalse();
        assertThat(limiter.allowCodeIssue("owner@example.com","client",START.plus(ISSUE_WINDOW))).isTrue();

        verify(recoveryHmac,atLeastOnce()).digest("password-recovery-rate-limit:issue-address","owner@example.com");
        verify(recoveryHmac,atLeastOnce()).digest("password-recovery-rate-limit:issue-client","client");
        verify(recoveryHmac,atLeastOnce()).digest("password-recovery-rate-limit:reset-client","client");
    }



    @Test
    void verificationAndRecoveryMaintainIndependentQuotasWithTheSameSecretAndKeys() {
        EmailVerificationPolicy verificationPolicy = new EmailVerificationPolicy(Duration.ofMinutes(15),Duration.ofMinutes(1),5,ISSUE_WINDOW,1,1,SUBMISSION_WINDOW,1,100);
        PasswordRecoveryPolicy recoveryPolicy = new PasswordRecoveryPolicy(Duration.ofMinutes(15),Duration.ofMinutes(1),5,ISSUE_WINDOW,1,1,SUBMISSION_WINDOW,1,100);
        EmailVerificationRateLimiter verification = new EmailVerificationRateLimiter(new EmailVerificationHmac(ENCODED_SECRET),verificationPolicy);
        PasswordRecoveryRateLimiter recovery = new PasswordRecoveryRateLimiter(new PasswordRecoveryHmac(ENCODED_SECRET),recoveryPolicy);

        assertThat(verification.allowCodeIssue("owner@example.com","client",START)).isTrue();
        assertThat(verification.allowCodeIssue("owner@example.com","client",START)).isFalse();
        assertThat(verification.allowVerification("client",START)).isTrue();
        assertThat(verification.allowVerification("client",START)).isFalse();

        assertThat(recovery.allowCodeIssue("owner@example.com","client",START)).isTrue();
        assertThat(recovery.allowCodeIssue("owner@example.com","client",START)).isFalse();
        assertThat(recovery.allowPasswordReset("client",START)).isTrue();
        assertThat(recovery.allowPasswordReset("client",START)).isFalse();
    }



    private OneTimeCodeRateLimiter limiter(int addressLimit,int clientLimit,int submissionLimit,int capacity) {
        return new OneTimeCodeRateLimiter(hmac,policy(addressLimit,clientLimit,submissionLimit,capacity),PURPOSE_PREFIX,SUBMISSION_SCOPE);
    }



    private static OneTimeCodePolicy policy(int addressLimit,int clientLimit,int submissionLimit,int capacity) {
        return new OneTimeCodePolicy(Duration.ofMinutes(15),Duration.ofMinutes(1),5,ISSUE_WINDOW,addressLimit,clientLimit,SUBMISSION_WINDOW,submissionLimit,capacity,"Test") {};
    }
}