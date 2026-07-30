package com.enginertugrul.iottemperaturemonitor.security.verification;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

@Getter
@Component
public class EmailVerificationPolicy {

    private final Duration codeLifetime;
    private final Duration resendCooldown;
    private final int maximumFailedAttempts;
    private final Duration issueRateLimitWindow;
    private final int maximumIssueRequestsPerAddress;
    private final int maximumIssueRequestsPerClient;
    private final Duration verificationRateLimitWindow;
    private final int maximumVerificationRequestsPerClient;
    private final int maximumTrackedRateLimitKeys;

    public EmailVerificationPolicy(
            @Value("${app.security.email-verification.code-lifetime:PT15M}") Duration codeLifetime,
            @Value("${app.security.email-verification.resend-cooldown:PT1M}") Duration resendCooldown,
            @Value("${app.security.email-verification.maximum-failed-attempts:5}") int maximumFailedAttempts,
            @Value("${app.security.email-verification.issue-rate-limit-window:PT15M}") Duration issueRateLimitWindow,
            @Value("${app.security.email-verification.maximum-issue-requests-per-address:5}") int maximumIssueRequestsPerAddress,
            @Value("${app.security.email-verification.maximum-issue-requests-per-client:20}") int maximumIssueRequestsPerClient,
            @Value("${app.security.email-verification.verification-rate-limit-window:PT15M}") Duration verificationRateLimitWindow,
            @Value("${app.security.email-verification.maximum-verification-requests-per-client:30}") int maximumVerificationRequestsPerClient,
            @Value("${app.security.email-verification.maximum-tracked-rate-limit-keys:100}") int maximumTrackedRateLimitKeys
    ) {
        this.codeLifetime = Objects.requireNonNull(codeLifetime, "codeLifetime must not be null");
        this.resendCooldown = Objects.requireNonNull(resendCooldown, "resendCooldown must not be null");
        this.issueRateLimitWindow = Objects.requireNonNull(issueRateLimitWindow, "issueRateLimitWindow must not be null");
        this.verificationRateLimitWindow = Objects.requireNonNull(verificationRateLimitWindow, "verificationRateLimitWindow must not be null");

        if (codeLifetime.isZero() || codeLifetime.isNegative()) {
            throw new IllegalArgumentException("codeLifetime must be positive");
        }

        if (resendCooldown.isZero() || resendCooldown.isNegative() || resendCooldown.compareTo(codeLifetime) >= 0) {
            throw new IllegalArgumentException("resendCooldown must be positive and shorter than codeLifetime");
        }

        if (maximumFailedAttempts < 1 || maximumFailedAttempts > 10) {
            throw new IllegalArgumentException("maximumFailedAttempts must be between 1 and 10");
        }

        if (issueRateLimitWindow.isZero() || issueRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("issueRateLimitWindow must be positive");
        }

        if (verificationRateLimitWindow.isZero() || verificationRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("verificationRateLimitWindow must be positive");
        }

        if (maximumIssueRequestsPerAddress < 1 || maximumIssueRequestsPerClient < 1
                || maximumVerificationRequestsPerClient < 1 || maximumTrackedRateLimitKeys < 1) {
            throw new IllegalArgumentException("Email verification rate limits must be positive");
        }

        this.maximumFailedAttempts = maximumFailedAttempts;
        this.maximumIssueRequestsPerAddress = maximumIssueRequestsPerAddress;
        this.maximumIssueRequestsPerClient = maximumIssueRequestsPerClient;
        this.maximumVerificationRequestsPerClient = maximumVerificationRequestsPerClient;
        this.maximumTrackedRateLimitKeys = maximumTrackedRateLimitKeys;
    }

}