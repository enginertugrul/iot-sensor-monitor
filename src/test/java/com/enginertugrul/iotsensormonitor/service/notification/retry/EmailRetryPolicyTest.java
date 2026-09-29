package com.enginertugrul.iotsensormonitor.service.notification.retry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class EmailRetryPolicyTest {



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0})
    void rejectsMaximumAttemptsBelowOne(int maximumAttempts) {
        assertThatThrownBy(() -> policy(maximumAttempts,Duration.ZERO,1.0,Duration.ofSeconds(1),Duration.ZERO))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumAttempts must be at least 1");
    }



    @Test
    void rejectsMaximumDelayShorterThanInitialDelay() {
        assertThatThrownBy(() -> policy(3,Duration.ofSeconds(2),2.0,Duration.ofSeconds(1),Duration.ZERO))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumDelay must not be shorter than initialDelay");
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,-1_000_000_000})
    void rejectsNegativeInitialDelayAndJitter(long nanos) {
        Duration negative = Duration.ofNanos(nanos);

        assertThatThrownBy(() -> policy(3,negative,2.0,Duration.ofSeconds(5),Duration.ZERO))
                .isExactlyInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> policy(3,Duration.ofSeconds(1),2.0,Duration.ofSeconds(5),negative))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,0})
    void rejectsNonPositiveMaximumDelay(long millis) {
        assertThatThrownBy(() -> policy(3,Duration.ZERO,1.0,Duration.ofMillis(millis),Duration.ZERO))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }



    @ParameterizedTest
    @ValueSource(doubles = {-1.0,0.0,0.99,Double.NaN,Double.NEGATIVE_INFINITY})
    void rejectsMultipliersThatAreNotAtLeastOne(double multiplier) {
        assertThatThrownBy(() -> policy(3,Duration.ofSeconds(1),multiplier,Duration.ofSeconds(5),Duration.ZERO))
                .isExactlyInstanceOf(IllegalArgumentException.class);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,2,3,5})
    void countsTheInitialSendWithinMaximumAttempts(int maximumAttempts) {
        EmailRetryPolicy policy = policy(maximumAttempts,Duration.ZERO,1.0,Duration.ofSeconds(1),Duration.ZERO);
        BackOffExecution execution = policy.getRetryPolicy().getBackOff().start();

        assertThat(policy.getMaximumAttempts()).isEqualTo(maximumAttempts);

        for (int retry = 1; retry < maximumAttempts; retry++) {
            assertThat(execution.nextBackOff()).as("delay before retry %s",retry).isZero();
        }

        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }



    @Test
    void increasesBackoffExponentiallyWithoutExceedingMaximumDelay() {
        EmailRetryPolicy policy = policy(6,Duration.ofMillis(100),2.0,Duration.ofMillis(250),Duration.ZERO);
        BackOffExecution execution = policy.getRetryPolicy().getBackOff().start();
        long[] expectedDelays = {100,200,250,250,250};

        for (long expectedDelay : expectedDelays) {
            assertThat(execution.nextBackOff()).isEqualTo(expectedDelay);
        }

        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }



    @ParameterizedTest
    @CsvSource({"1.0,500","2.0,100"})
    void supportsFixedDelaysAndMaximumDelayEqualToInitialDelay(double multiplier,long maximumDelayMillis) {
        EmailRetryPolicy policy = policy(4,Duration.ofMillis(100),multiplier,Duration.ofMillis(maximumDelayMillis),Duration.ZERO);
        BackOffExecution execution = policy.getRetryPolicy().getBackOff().start();

        assertThat(execution.nextBackOff()).isEqualTo(100);
        assertThat(execution.nextBackOff()).isEqualTo(100);
        assertThat(execution.nextBackOff()).isEqualTo(100);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }



    @Test
    void passesConfiguredJitterToTheBackoffStrategy() {
        EmailRetryPolicy policy = policy(3,Duration.ofSeconds(1),2.0,Duration.ofSeconds(5),Duration.ofMillis(200));

        assertThat(policy.getRetryPolicy().getBackOff())
                .isInstanceOfSatisfying(ExponentialBackOff.class,backOff -> assertThat(backOff.getJitter()).isEqualTo(200));
    }



    @Test
    void appliesTheEmailFailureClassifierToRetryDecisions() {
        RetryPolicy retryPolicy = policy(3,Duration.ZERO,1.0,Duration.ofSeconds(1),Duration.ZERO).getRetryPolicy();
        MailSendException temporaryFailure = new MailSendException("Temporary delivery failure");
        MailSendException permanentFailure =
                new MailSendException("Delivery failed",new MailAuthenticationException("Authentication failed"));

        assertThat(retryPolicy.shouldRetry(temporaryFailure)).isTrue();
        assertThat(retryPolicy.shouldRetry(permanentFailure)).isFalse();
        assertThat(retryPolicy.shouldRetry(new IllegalStateException("Wrapped failure",temporaryFailure))).isFalse();
    }



    private static EmailRetryPolicy policy(int maximumAttempts,Duration initialDelay,double multiplier,Duration maximumDelay,Duration jitter) {
        return new EmailRetryPolicy(new EmailRetryFailureClassifier(),maximumAttempts,initialDelay,multiplier,maximumDelay,jitter);
    }
}