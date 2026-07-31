package com.enginertugrul.iottemperaturemonitor.security.recovery;

import com.enginertugrul.iottemperaturemonitor.security.onetimecode.OneTimeCodePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;



@Component
public class PasswordRecoveryPolicy extends OneTimeCodePolicy {



    public PasswordRecoveryPolicy(
            @Value("${app.security.password-recovery.code-lifetime:PT15M}") Duration codeLifetime,
            @Value("${app.security.password-recovery.resend-cooldown:PT1M}") Duration resendCooldown,
            @Value("${app.security.password-recovery.maximum-failed-attempts:5}") int maximumFailedAttempts,
            @Value("${app.security.password-recovery.issue-rate-limit-window:PT15M}") Duration issueRateLimitWindow,
            @Value("${app.security.password-recovery.maximum-issue-requests-per-address:5}") int maximumIssueRequestsPerAddress,
            @Value("${app.security.password-recovery.maximum-issue-requests-per-client:20}") int maximumIssueRequestsPerClient,
            @Value("${app.security.password-recovery.reset-rate-limit-window:PT15M}") Duration resetRateLimitWindow,
            @Value("${app.security.password-recovery.maximum-reset-requests-per-client:30}") int maximumResetRequestsPerClient,
            @Value("${app.security.password-recovery.maximum-tracked-rate-limit-keys:100}") int maximumTrackedRateLimitKeys
    ) {

        super(
                codeLifetime,
                resendCooldown,
                maximumFailedAttempts,
                issueRateLimitWindow,
                maximumIssueRequestsPerAddress,
                maximumIssueRequestsPerClient,
                resetRateLimitWindow,
                maximumResetRequestsPerClient,
                maximumTrackedRateLimitKeys,
                "Password recovery"
        );

    }






}