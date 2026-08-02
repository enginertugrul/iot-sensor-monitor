package com.enginertugrul.iotsensormonitor.security.recovery;


import com.enginertugrul.iotsensormonitor.security.onetimecode.GeneratedOneTimeCode;
import com.enginertugrul.iotsensormonitor.security.onetimecode.OneTimeCodeGenerator;
import com.enginertugrul.iotsensormonitor.security.onetimecode.SecureRandomNumericOneTimeCodeGenerator;
import org.springframework.stereotype.Component;


@Component
public class SecureRandomPasswordRecoveryCodeGenerator implements PasswordRecoveryCodeGenerator{

    private static final String CODE_PURPOSE ="password-reset-code";

    private final OneTimeCodeGenerator delegate;




    public SecureRandomPasswordRecoveryCodeGenerator(PasswordRecoveryHmac passwordRecoveryHmac) {

        this.delegate = new SecureRandomNumericOneTimeCodeGenerator(passwordRecoveryHmac,CODE_PURPOSE);

    }



    @Override
    public GeneratedOneTimeCode generate(Long userId) {
        return delegate.generate(userId);
    }



    @Override
    public boolean matches(Long userId, String rawCode, String codeHash) {
        return delegate.matches(userId,rawCode,codeHash);
    }




}
