package com.enginertugrul.iotsensormonitor.service.notification.retry;

import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;



class EmailRetryFailureClassifierTest {

    private final EmailRetryFailureClassifier classifier = new EmailRetryFailureClassifier();



    @ParameterizedTest
    @NullSource
    @MethodSource("nonMailSendFailures")
    void requiresMailSendExceptionAtTheRoot(Throwable failure) {
        assertThat(classifier.isRetryable(failure)).isFalse();
    }



    @ParameterizedTest
    @MethodSource("retryableMailFailures")
    void retriesSendFailuresWithoutPermanentFailureEvidence(MailSendException failure) {
        assertThat(classifier.isRetryable(failure)).isTrue();
    }



    @ParameterizedTest
    @CsvSource({
            "421,true",
            "450,true",
            "499,true",
            "500,false",
            "550,false",
            "599,false",
            "600,true"
    })
    void classifiesSmtpSendAndAddressReturnCodes(int returnCode,boolean expected) throws AddressException {
        InternetAddress recipient = new InternetAddress("recipient@example.com");
        SMTPSendFailedException sendFailure =
                new SMTPSendFailedException("DATA",returnCode,"SMTP send failed",null,null,null,null);
        SMTPAddressFailedException addressFailure =
                new SMTPAddressFailedException(recipient,"RCPT TO",returnCode,"SMTP address failed");

        assertThat(classifier.isRetryable(new MailSendException("Delivery failed",sendFailure))).isEqualTo(expected);
        assertThat(classifier.isRetryable(new MailSendException("Delivery failed",addressFailure))).isEqualTo(expected);
    }



    @ParameterizedTest
    @MethodSource("permanentCauses")
    void rejectsPermanentFailuresInsideNestedCauses(Exception permanentFailure) {
        IllegalStateException transportFailure = new IllegalStateException("Transport failed",permanentFailure);
        MailSendException failure = new MailSendException("Delivery failed",transportFailure);

        assertThat(classifier.isRetryable(failure)).isFalse();
    }



    @ParameterizedTest
    @MethodSource("permanentCauses")
    void findsPermanentBatchFailuresDespiteRepeatedAndCyclicCauses(Exception permanentFailure) {
        RuntimeException sharedCause = cyclicCause();
        Map<Object,Exception> failedMessages = new LinkedHashMap<>();
        failedMessages.put("first-message",sharedCause);
        failedMessages.put("second-message",sharedCause);
        failedMessages.put("third-message",new MailSendException("Nested delivery failed",permanentFailure));
        MailSendException failure = new MailSendException("Batch delivery failed",sharedCause,failedMessages);

        boolean retryable = assertTimeoutPreemptively(Duration.ofSeconds(2),() -> classifier.isRetryable(failure));

        assertThat(retryable).isFalse();
    }



    @ParameterizedTest
    @CsvSource({
            "false,false,false,true",
            "false,true,false,true",
            "false,false,true,false",
            "true,false,false,false",
            "true,true,false,false",
            "true,true,true,false"
    })
    void classifiesRecipientOutcomesBeforeRetryingTemporarySmtpFailures(
            boolean hasSent,boolean hasUnsent,boolean hasInvalid,boolean expected) throws AddressException {
        Address[] recipients = {new InternetAddress("recipient@example.com")};
        Address[] validSent = hasSent ? recipients : null;
        Address[] validUnsent = hasUnsent ? recipients : null;
        Address[] invalid = hasInvalid ? recipients : null;
        SendFailedException sendFailure =
                new SendFailedException("Delivery failed",null,validSent,validUnsent,invalid);
        SMTPSendFailedException smtpFailure =
                new SMTPSendFailedException("DATA",421,"Temporary SMTP failure",null,validSent,validUnsent,invalid);

        assertThat(classifier.isRetryable(new MailSendException("Delivery failed",sendFailure))).isEqualTo(expected);
        assertThat(classifier.isRetryable(new MailSendException("Delivery failed",smtpFailure))).isEqualTo(expected);
    }



    @Test
    void treatsEmptyRecipientArraysAsNoEvidenceOfPartialDeliveryOrInvalidRecipients() {
        Address[] empty = new Address[0];
        SendFailedException sendFailure = new SendFailedException("Delivery failed",null,empty,empty,empty);

        assertThat(classifier.isRetryable(new MailSendException("Delivery failed",sendFailure))).isTrue();
    }



    @Test
    void followsJakartaMailNextExceptionChains() {
        MessagingException transportFailure = new MessagingException("Transport failed");
        MessagingException relayFailure = new MessagingException("Relay failed");
        transportFailure.setNextException(relayFailure);
        relayFailure.setNextException(new AuthenticationFailedException("Authentication failed"));
        MailSendException failure = new MailSendException("Delivery failed",transportFailure);

        assertThat(classifier.isRetryable(failure)).isFalse();
    }



    @Test
    void terminatesWhenOrdinaryCausesContainACycle() {
        MailSendException failure = new MailSendException("Delivery failed",cyclicCause());

        boolean retryable = assertTimeoutPreemptively(Duration.ofSeconds(2),() -> classifier.isRetryable(failure));

        assertThat(retryable).isTrue();
    }



    @Test
    void terminatesWhenJakartaMailNextExceptionsContainACycle() {
        MessagingException first = new MessagingException("First failure");
        MessagingException second = new MessagingException("Second failure");
        first.setNextException(second);
        second.setNextException(first);
        MailSendException failure = new MailSendException("Delivery failed",first);

        boolean retryable = assertTimeoutPreemptively(Duration.ofSeconds(2),() -> classifier.isRetryable(failure));

        assertThat(retryable).isTrue();
    }



    private static Stream<Throwable> nonMailSendFailures() {
        return Stream.of(
                new IllegalStateException("Unexpected failure"),
                new SocketTimeoutException("Connection timed out"),
                new MailAuthenticationException("Authentication failed"),
                new MailParseException("Message could not be parsed"),
                new MailPreparationException("Message could not be prepared"),
                new IllegalStateException("Wrapped failure",new MailSendException("Delivery failed"))
        );
    }



    private static Stream<MailSendException> retryableMailFailures() {
        return Stream.of(
                new MailSendException("Delivery failed"),
                new MailSendException("Delivery failed",new SocketTimeoutException("Connection timed out")),
                new MailSendException("Delivery failed",new ConnectException("Connection refused")),
                new MailSendException("Delivery failed",new IllegalStateException("Transport failed",new IOException("Connection lost"))),
                new MailSendException(Map.of("first-message",new IOException("Connection lost"),
                        "second-message",new SocketTimeoutException("Connection timed out")))
        );
    }


    private static Stream<Exception> permanentCauses() {
        return Stream.of(
                new MailAuthenticationException("Authentication failed"),
                new AuthenticationFailedException("Authentication failed"),
                new MailParseException("Message could not be parsed"),
                new MailPreparationException("Message could not be prepared"),
                new SSLHandshakeException("TLS handshake failed"),
                new InterruptedException("Delivery interrupted")
        );
    }



    private static RuntimeException cyclicCause() {
        RuntimeException first = new RuntimeException("First failure");
        RuntimeException second = new RuntimeException("Second failure",first);
        first.initCause(second);
        return first;
    }
}