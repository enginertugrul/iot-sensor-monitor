package com.enginertugrul.iotsensormonitor.service.notification.retry;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.stereotype.Component;

import java.time.Duration;



@Getter
@Component
public class EmailRetryPolicy {


    private final int maximumAttempts;
    private final RetryPolicy retryPolicy;



    public EmailRetryPolicy(
            EmailRetryFailureClassifier failureClassifier,
            @Value("${app.mail.retry.maximum-attempts:3}") int maximumAttempts,
            @Value("${app.mail.retry.initial-delay:PT1S}") Duration initialDelay,
            @Value("${app.mail.retry.multiplier:2.0}") double multiplier,
            @Value("${app.mail.retry.maximum-delay:PT5S}") Duration maximumDelay,
            @Value("${app.mail.retry.jitter:PT0.2S}") Duration jitter
    ) {

        if (maximumAttempts < 1) {
            throw new IllegalArgumentException("maximumAttempts must be at least 1");
        }

        if (maximumDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maximumDelay must not be shorter than initialDelay");
        }

        this.maximumAttempts = maximumAttempts;
        this.retryPolicy = RetryPolicy.builder()
                .maxRetries(maximumAttempts - 1L)
                .delay(initialDelay)
                .multiplier(multiplier)
                .maxDelay(maximumDelay)
                .jitter(jitter)
                .predicate(failureClassifier::isRetryable)
                .build();
    }
}