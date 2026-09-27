package com.enginertugrul.iotsensormonitor.security.onetimecode;

import com.enginertugrul.iotsensormonitor.security.recovery.PasswordRecoveryPolicy;
import com.enginertugrul.iotsensormonitor.security.verification.EmailVerificationPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;



class OneTimeCodePolicyTest {

    private static final Duration LIFETIME = Duration.ofMinutes(15);
    private static final Duration COOLDOWN = Duration.ofMinutes(1);
    private static final Duration ISSUE_WINDOW = Duration.ofMinutes(2);
    private static final Duration SUBMISSION_WINDOW = Duration.ofMinutes(3);



    @ParameterizedTest
    @MethodSource("concretePolicies")
    void concretePoliciesPreserveAllConfiguredValues(OneTimeCodePolicy policy) {
        assertThat(policy.getCodeLifetime()).isEqualTo(LIFETIME);
        assertThat(policy.getResendCooldown()).isEqualTo(COOLDOWN);
        assertThat(policy.getMaximumFailedAttempts()).isEqualTo(7);
        assertThat(policy.getIssueRateLimitWindow()).isEqualTo(ISSUE_WINDOW);
        assertThat(policy.getMaximumIssueRequestsPerAddress()).isEqualTo(2);
        assertThat(policy.getMaximumIssueRequestsPerClient()).isEqualTo(3);
        assertThat(policy.getSubmissionRateLimitWindow()).isEqualTo(SUBMISSION_WINDOW);
        assertThat(policy.getMaximumSubmissionRequestsPerClient()).isEqualTo(4);
        assertThat(policy.getMaximumTrackedRateLimitKeys()).isEqualTo(100);
    }



    @Test
    void requiresEveryDuration() {
        assertThatNullPointerException()
                .isThrownBy(() -> policy(null,COOLDOWN,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("codeLifetime must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> policy(LIFETIME,null,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("resendCooldown must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,null,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("issueRateLimitWindow must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,3,null,4,100,"Test"))
                .withMessage("submissionRateLimitWindow must not be null");
    }



    @ParameterizedTest
    @MethodSource("nonPositiveDurations")
    void rejectsNonPositiveLifetimesAndRateLimitWindows(Duration duration) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(duration,COOLDOWN,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("codeLifetime must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,duration,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("issueRateLimitWindow must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,3,duration,4,100,"Test"))
                .withMessage("submissionRateLimitWindow must be positive");
    }



    @ParameterizedTest
    @MethodSource("invalidCooldowns")
    void rejectsCooldownsOutsideTheOpenLifetimeInterval(Duration cooldown) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,cooldown,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("resendCooldown must be positive and shorter than codeLifetime");
    }



    @Test
    void acceptsACooldownOneNanosecondShorterThanTheLifetime() {
        Duration cooldown = LIFETIME.minusNanos(1);
        OneTimeCodePolicy policy = policy(LIFETIME,cooldown,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test");

        assertThat(policy.getResendCooldown()).isEqualTo(cooldown);
    }



    @Test
    void acceptsPositiveDurationsAtNanosecondPrecision() {
        Duration lifetime = Duration.ofNanos(2);
        Duration minimumDuration = Duration.ofNanos(1);
        OneTimeCodePolicy policy = policy(lifetime,minimumDuration,1,minimumDuration,1,1,minimumDuration,1,1,"Test");

        assertThat(policy.getCodeLifetime()).isEqualTo(lifetime);
        assertThat(policy.getResendCooldown()).isEqualTo(minimumDuration);
        assertThat(policy.getIssueRateLimitWindow()).isEqualTo(minimumDuration);
        assertThat(policy.getSubmissionRateLimitWindow()).isEqualTo(minimumDuration);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,5,10})
    void acceptsFailedAttemptLimitsWithinInclusiveBounds(int attempts) {
        OneTimeCodePolicy policy = policy(LIFETIME,COOLDOWN,attempts,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test");

        assertThat(policy.getMaximumFailedAttempts()).isEqualTo(attempts);
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0,11,Integer.MAX_VALUE})
    void rejectsFailedAttemptLimitsOutsideInclusiveBounds(int attempts) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,attempts,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("maximumFailedAttempts must be between 1 and 10");
    }



    @ParameterizedTest
    @ValueSource(ints = {1,Integer.MAX_VALUE})
    void acceptsPositiveRequestLimitsAndCounterCapacity(int limit) {
        OneTimeCodePolicy policy = policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,limit,limit,SUBMISSION_WINDOW,limit,limit,"Test");

        assertThat(policy.getMaximumIssueRequestsPerAddress()).isEqualTo(limit);
        assertThat(policy.getMaximumIssueRequestsPerClient()).isEqualTo(limit);
        assertThat(policy.getMaximumSubmissionRequestsPerClient()).isEqualTo(limit);
        assertThat(policy.getMaximumTrackedRateLimitKeys()).isEqualTo(limit);
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0})
    void rejectsEachNonPositiveRequestLimitAndCounterCapacity(int limit) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,limit,3,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("Test rate limits must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,limit,SUBMISSION_WINDOW,4,100,"Test"))
                .withMessage("Test rate limits must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,limit,100,"Test"))
                .withMessage("Test rate limits must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,limit,"Test"))
                .withMessage("Test rate limits must be positive");
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void requiresANonBlankPolicyName(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> policy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100,name))
                .withMessage("policyName must not be blank");
    }



    @Test
    void concretePoliciesIdentifyTheirOwnInvalidRateLimits() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new EmailVerificationPolicy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,0,3,SUBMISSION_WINDOW,4,100))
                .withMessage("Email verification rate limits must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new PasswordRecoveryPolicy(LIFETIME,COOLDOWN,5,ISSUE_WINDOW,0,3,SUBMISSION_WINDOW,4,100))
                .withMessage("Password recovery rate limits must be positive");
    }



    private static Stream<OneTimeCodePolicy> concretePolicies() {
        return Stream.of(
                new EmailVerificationPolicy(LIFETIME,COOLDOWN,7,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100),
                new PasswordRecoveryPolicy(LIFETIME,COOLDOWN,7,ISSUE_WINDOW,2,3,SUBMISSION_WINDOW,4,100)
        );
    }



    private static Stream<Duration> nonPositiveDurations() {
        return Stream.of(Duration.ZERO,Duration.ofNanos(-1),Duration.ofSeconds(-1));
    }



    private static Stream<Duration> invalidCooldowns() {
        return Stream.of(Duration.ZERO,Duration.ofNanos(-1),LIFETIME,LIFETIME.plusNanos(1));
    }



    private static OneTimeCodePolicy policy(Duration lifetime,Duration cooldown,int attempts,Duration issueWindow,
                                            int addressLimit,int clientLimit,Duration submissionWindow,int submissionLimit,int capacity,String name) {
        return new OneTimeCodePolicy(lifetime,cooldown,attempts,issueWindow,addressLimit,clientLimit,submissionWindow,submissionLimit,capacity,name) {};
    }
}