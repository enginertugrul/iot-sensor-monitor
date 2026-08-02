package com.enginertugrul.iottemperaturemonitor.security.ingestion;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;




@Component
public class SecureRandomSensorIngestionTokenGenerator implements SensorIngestionTokenGenerator {

    private static final int TOKEN_BYTE_LENGTH = 32;

    private final SecureRandom secureRandom = new SecureRandom();




    @Override
    public GeneratedSensorIngestionToken generate() {

        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);

        String rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String tokenHash = hash(rawToken);

        return new GeneratedSensorIngestionToken(rawToken, tokenHash);
    }




    @Override
    public String hash(String rawToken) {

        String normalizedToken = normalize(rawToken);

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }




    private String normalize(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("sensor ingestion token must not be blank");
        }

        return rawToken.trim();
    }


}