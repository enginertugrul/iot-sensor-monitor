package com.enginertugrul.iottemperaturemonitor.security.verification;

import com.enginertugrul.iottemperaturemonitor.security.onetimecode.OneTimeCodeRateLimiter;
import org.springframework.stereotype.Component;

import java.time.Instant;



@Component
public class EmailVerificationRateLimiter {

    private static final String RATE_LIMIT_PURPOSE_PREFIX = "email-verification-rate-limit:";

    private static final String VERIFICATION_SCOPE = "verification-client";

    private final OneTimeCodeRateLimiter delegate;



    public EmailVerificationRateLimiter(EmailVerificationHmac emailVerificationHmac,EmailVerificationPolicy policy) {
        this.delegate = new OneTimeCodeRateLimiter(
                emailVerificationHmac,
                policy,
                RATE_LIMIT_PURPOSE_PREFIX,
                VERIFICATION_SCOPE
        );
    }



    public boolean allowCodeIssue(String addressKey, String clientKey, Instant requestedAt) {
        return delegate.allowCodeIssue(addressKey,clientKey,requestedAt);
    }



    public boolean allowVerification(String clientKey, Instant requestedAt) {
        return delegate.allowSubmission(clientKey, requestedAt);
    }


}