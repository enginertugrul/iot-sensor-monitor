package com.enginertugrul.iottemperaturemonitor.security.onetimecode;

import lombok.Getter;

import java.time.Duration;
import java.util.Objects;



@Getter
public abstract class OneTimeCodePolicy {

    private final Duration codeLifetime;
    private final Duration resendCooldown;
    private final int maximumFailedAttempts;
    private final Duration issueRateLimitWindow;
    private final int maximumIssueRequestsPerAddress;
    private final int maximumIssueRequestsPerClient;
    private final Duration submissionRateLimitWindow;
    private final int maximumSubmissionRequestsPerClient;
    private final int maximumTrackedRateLimitKeys;




    protected OneTimeCodePolicy(
            Duration codeLifetime,
            Duration resendCooldown,
            int maximumFailedAttempts,
            Duration issueRateLimitWindow,
            int maximumIssueRequestsPerAddress,
            int maximumIssueRequestsPerClient,
            Duration submissionRateLimitWindow,
            int maximumSubmissionRequestsPerClient,
            int maximumTrackedRateLimitKeys,
            String policyName
    ) {

        String requiredPolicyName = requireText(policyName,"policyName");

        this.codeLifetime = Objects.requireNonNull(codeLifetime,"codeLifetime must not be null");

        this.resendCooldown = Objects.requireNonNull(resendCooldown,"resendCooldown must not be null");

        this.issueRateLimitWindow = Objects.requireNonNull(issueRateLimitWindow,"issueRateLimitWindow must not be null");

        this.submissionRateLimitWindow = Objects.requireNonNull(submissionRateLimitWindow,"submissionRateLimitWindow must not be null");

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

        if (submissionRateLimitWindow.isZero() || submissionRateLimitWindow.isNegative()) {
            throw new IllegalArgumentException("submissionRateLimitWindow must be positive");
        }

        if (maximumIssueRequestsPerAddress < 1
                || maximumIssueRequestsPerClient < 1
                || maximumSubmissionRequestsPerClient < 1
                || maximumTrackedRateLimitKeys < 1) {

            throw new IllegalArgumentException(requiredPolicyName + " rate limits must be positive");
        }

        this.maximumFailedAttempts = maximumFailedAttempts;
        this.maximumIssueRequestsPerAddress = maximumIssueRequestsPerAddress;
        this.maximumIssueRequestsPerClient = maximumIssueRequestsPerClient;
        this.maximumSubmissionRequestsPerClient = maximumSubmissionRequestsPerClient;
        this.maximumTrackedRateLimitKeys = maximumTrackedRateLimitKeys;

    }




    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value;
    }





}