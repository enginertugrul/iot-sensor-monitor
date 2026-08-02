package com.enginertugrul.iotsensormonitor.security.recovery;

import com.enginertugrul.iotsensormonitor.security.onetimecode.OneTimeCodeRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Instant;



@Component
public class PasswordRecoveryRateLimiter {

    private static final String RATE_LIMIT_PURPOSE_PREFIX = "password-recovery-rate-limit:";

    private static final String RESET_SCOPE = "reset-client";

    private final OneTimeCodeRateLimiter delegate;




    public PasswordRecoveryRateLimiter(PasswordRecoveryHmac passwordRecoveryHmac,PasswordRecoveryPolicy policy) {
        this.delegate = new OneTimeCodeRateLimiter(
                passwordRecoveryHmac,
                policy,
                RATE_LIMIT_PURPOSE_PREFIX,
                RESET_SCOPE
        );
    }


    public boolean allowCodeIssue(String addressKey,String clientKey,Instant requestedAt) {
        return delegate.allowCodeIssue(addressKey,clientKey,requestedAt);
    }



    public boolean allowPasswordReset(String clientKey, Instant attemptedAt) {
        return delegate.allowSubmission(clientKey,attemptedAt);
    }


}