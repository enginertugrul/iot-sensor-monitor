package com.enginertugrul.iotsensormonitor.security.verification;

import com.enginertugrul.iotsensormonitor.security.onetimecode.HmacDigest;
import com.enginertugrul.iotsensormonitor.security.onetimecode.HmacSha256;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;


@Component
public class EmailVerificationHmac implements HmacDigest {


    private final HmacDigest delegate;

    public EmailVerificationHmac ( @Value("${app.security.email-verification.code-hmac-secret-base64}") String encodedSecret) {

        this.delegate = new HmacSha256(encodedSecret, "Email verification HMAC secret");

    }



    @Override
    public String digest(String purpose, String value) {
        return delegate.digest(purpose,value);
    }


    @Override
    public boolean matches(String purpose, String value, String expectedDigest) {
        return delegate.matches(purpose,value,expectedDigest);
    }

}