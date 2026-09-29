package com.enginertugrul.iotsensormonitor.service.notification.retry;

import jakarta.mail.AuthenticationFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class EmailDeliveryRetryServiceTest {



    @ParameterizedTest
    @CsvSource({
            "1,1",
            "3,1",
            "3,2",
            "3,3",
            "5,5"
    })
    void stopsImmediatelyAfterASuccessfulAttempt(int maximumAttempts,int successfulAttempt) {
        EmailDeliveryRetryService service = service(maximumAttempts);
        AtomicInteger attempts = new AtomicInteger();
        Runnable sendOperation = () -> {
            if (attempts.incrementAndGet() < successfulAttempt) {
                throw new MailSendException("Temporary delivery failure");
            }
        };

        assertThatCode(() -> service.send("ALERT",42L,sendOperation)).doesNotThrowAnyException();

        assertThat(attempts.get()).isEqualTo(successfulAttempt);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,2,3,5})
    void exhaustsExactlyTheConfiguredNumberOfAttempts(int maximumAttempts) {
        EmailDeliveryRetryService service = service(maximumAttempts);
        AtomicInteger attempts = new AtomicInteger();
        MailSendException failure =
                new MailSendException("Delivery failed",new SocketTimeoutException("Connection timed out"));
        Runnable sendOperation = () -> {
            attempts.incrementAndGet();
            throw failure;
        };

        assertThatThrownBy(() -> service.send("ALERT",42L,sendOperation)).isSameAs(failure);

        assertThat(attempts.get()).isEqualTo(maximumAttempts);
    }



    @Test
    void propagatesTheLastOriginalExceptionAfterExhaustion() {
        EmailDeliveryRetryService service = service(3);
        AtomicInteger attempts = new AtomicInteger();
        List<MailSendException> failures = List.of(
                new MailSendException("First delivery failure"),
                new MailSendException("Second delivery failure"),
                new MailSendException("Final delivery failure")
        );
        Runnable sendOperation = () -> {
            throw failures.get(attempts.getAndIncrement());
        };

        assertThatThrownBy(() -> service.send("ALERT",42L,sendOperation)).isSameAs(failures.getLast());

        assertThat(attempts.get()).isEqualTo(3);
    }



    @ParameterizedTest
    @MethodSource("nonRetryableFailures")
    void propagatesNonRetryableFailuresAfterOneAttempt(RuntimeException failure) {
        EmailDeliveryRetryService service = service(5);
        AtomicInteger attempts = new AtomicInteger();
        Runnable sendOperation = () -> {
            attempts.incrementAndGet();
            throw failure;
        };

        assertThatThrownBy(() -> service.send("ALERT",42L,sendOperation)).isSameAs(failure);

        assertThat(attempts.get()).isEqualTo(1);
    }



    @Test
    void stopsWhenARetryChangesFromTemporaryToPermanentFailure() {
        EmailDeliveryRetryService service = service(5);
        AtomicInteger attempts = new AtomicInteger();
        MailSendException temporaryFailure = new MailSendException("Temporary delivery failure");
        MailSendException permanentFailure =
                new MailSendException("Delivery failed",new MailAuthenticationException("Authentication failed"));
        Runnable sendOperation = () -> {
            if (attempts.incrementAndGet() == 1) {
                throw temporaryFailure;
            }
            throw permanentFailure;
        };

        assertThatThrownBy(() -> service.send("ALERT",42L,sendOperation)).isSameAs(permanentFailure);

        assertThat(attempts.get()).isEqualTo(2);
    }



    @Test
    void givesEachSendAFreshRetryBudgetAfterPreviousExhaustion() {
        EmailDeliveryRetryService service = service(2);
        AtomicInteger firstAttempts = new AtomicInteger();
        MailSendException firstFailure = new MailSendException("First notification failed");
        Runnable firstOperation = () -> {
            firstAttempts.incrementAndGet();
            throw firstFailure;
        };

        assertThatThrownBy(() -> service.send("ALERT",42L,firstOperation)).isSameAs(firstFailure);
        assertThat(firstAttempts.get()).isEqualTo(2);

        AtomicInteger nextAttempts = new AtomicInteger();
        Runnable nextOperation = () -> {
            if (nextAttempts.incrementAndGet() == 1) {
                throw new MailSendException("Temporary failure for next notification");
            }
        };

        assertThatCode(() -> service.send("ALERT",42L,nextOperation)).doesNotThrowAnyException();

        assertThat(nextAttempts.get()).isEqualTo(2);
    }



    private static Stream<RuntimeException> nonRetryableFailures() {
        return Stream.of(
                new IllegalStateException("Unexpected application failure"),
                new MailAuthenticationException("Authentication failed"),
                new MailSendException("Delivery failed",new AuthenticationFailedException("Authentication failed")),
                new MailSendException("Delivery failed",
                        new SMTPSendFailedException("DATA",550,"Permanent SMTP failure",null,null,null,null))
        );
    }



    private static EmailDeliveryRetryService service(int maximumAttempts) {
        EmailRetryPolicy policy =
                new EmailRetryPolicy(new EmailRetryFailureClassifier(),maximumAttempts,Duration.ZERO,1.0,Duration.ofSeconds(1),Duration.ZERO);
        return new EmailDeliveryRetryService(policy);
    }
}