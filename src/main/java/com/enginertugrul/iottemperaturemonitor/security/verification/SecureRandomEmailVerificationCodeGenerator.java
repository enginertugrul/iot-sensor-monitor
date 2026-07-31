package com.enginertugrul.iottemperaturemonitor.security.verification;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Pattern;

@Component
public class SecureRandomEmailVerificationCodeGenerator implements EmailVerificationCodeGenerator {

    private static final String CODE_PURPOSE = "email-verification-code";
    private static final int CODE_BOUND = 100_000_000;
    private static final Pattern CODE_PATTERN = Pattern.compile("[0-9]{8}");

    private final SecureRandom secureRandom = new SecureRandom();
    private final EmailVerificationHmac emailVerificationHmac;

    public SecureRandomEmailVerificationCodeGenerator(EmailVerificationHmac emailVerificationHmac) {
        this.emailVerificationHmac = emailVerificationHmac;
    }

    @Override
    public GeneratedEmailVerificationCode generate(Long userId) {
        long requiredUserId = requireUserId(userId);
        String rawCode = String.format(Locale.ROOT,"%08d",secureRandom.nextInt(CODE_BOUND));
        String codeHash = emailVerificationHmac.digest(CODE_PURPOSE,requiredUserId + ":" + rawCode);
        return new GeneratedEmailVerificationCode(rawCode,codeHash);
    }

    @Override
    public boolean matches(Long userId, String rawCode, String codeHash) {
        if (userId == null || userId < 1 || rawCode == null) {
            return false;
        }

        String normalizedCode = rawCode.trim();

        if (!CODE_PATTERN.matcher(normalizedCode).matches()) {
            return false;
        }

        return emailVerificationHmac.matches(CODE_PURPOSE,userId + ":" + normalizedCode,codeHash);
    }

    private long requireUserId(Long userId) {
        if (userId == null || userId < 1) {
            throw new IllegalArgumentException("userId must be positive");
        }

        return userId;
    }
}