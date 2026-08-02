package com.enginertugrul.iotsensormonitor.security.verification;

import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.onetimecode.OneTimeCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.onetimecode.SecureRandomNumericOneTimeCodeGenerator;
import org.springframework.stereotype.Component;





@Component
public class SecureRandomEmailVerificationCodeGenerator implements EmailVerificationCodeGenerator {

    private static final String CODE_PURPOSE = "email-verification-code";

    private final OneTimeCodeGenerator delegate;

    public SecureRandomEmailVerificationCodeGenerator(EmailVerificationHmac emailVerificationHmac) {
        this.delegate = new SecureRandomNumericOneTimeCodeGenerator(emailVerificationHmac,CODE_PURPOSE);
    }

    @Override
    public GeneratedOneTimeCode generate(Long userId) {
        return delegate.generate(userId);
    }

    @Override
    public boolean matches(Long userId, String rawCode, String codeHash) {
        return delegate.matches(userId, rawCode, codeHash);
    }

}