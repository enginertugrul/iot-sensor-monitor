package com.enginertugrul.iotsensormonitor.security.verification;

import com.enginertugrul.iotsensormonitor.security.onetimecode.OneTimeCodePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;



@Component
public class EmailVerificationPolicy extends OneTimeCodePolicy {

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

        super(
                codeLifetime,
                resendCooldown,
                maximumFailedAttempts,
                issueRateLimitWindow,
                maximumIssueRequestsPerAddress,
                maximumIssueRequestsPerClient,
                verificationRateLimitWindow,
                maximumVerificationRequestsPerClient,
                maximumTrackedRateLimitKeys,
                "Email verification"
        );

    }

}