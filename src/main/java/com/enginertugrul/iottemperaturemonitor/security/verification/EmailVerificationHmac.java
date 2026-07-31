package com.enginertugrul.iottemperaturemonitor.security.verification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class EmailVerificationHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final int MINIMUM_SECRET_BYTE_LENGTH = 32;

    private final SecretKeySpec secretKey;

    public EmailVerificationHmac(
            @Value("${app.security.email-verification.code-hmac-secret-base64}") String encodedSecret
    ) {
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalArgumentException("Email verification HMAC secret must not be blank");
        }

        byte[] secretBytes;

        try {
            secretBytes = Base64.getDecoder().decode(encodedSecret.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Email verification HMAC secret must be valid Base64",exception);
        }

        if (secretBytes.length < MINIMUM_SECRET_BYTE_LENGTH) {
            throw new IllegalArgumentException("Email verification HMAC secret must contain at least 32 bytes");
        }

        this.secretKey = new SecretKeySpec(secretBytes,HMAC_ALGORITHM);
        Arrays.fill(secretBytes,(byte) 0);
    }

    public String digest(String purpose, String value) {
        return HexFormat.of().formatHex(calculateDigest(purpose,value));
    }

    public boolean matches(String purpose, String value, String expectedDigest) {
        if (expectedDigest == null || expectedDigest.isBlank()) {
            return false;
        }

        byte[] expectedBytes;

        try {
            expectedBytes = HexFormat.of().parseHex(expectedDigest.trim());
        } catch (IllegalArgumentException exception) {
            return false;
        }

        return MessageDigest.isEqual(calculateDigest(purpose,value),expectedBytes);
    }

    private byte[] calculateDigest(String purpose, String value) {
        String requiredPurpose = requireText(purpose,"purpose");
        String requiredValue = requireText(value,"value");

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(secretKey);
            mac.update(requiredPurpose.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(requiredValue.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is not available",exception);
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }

        return value;
    }
}