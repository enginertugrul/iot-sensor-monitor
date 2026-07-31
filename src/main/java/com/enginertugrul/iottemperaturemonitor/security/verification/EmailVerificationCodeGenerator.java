package com.enginertugrul.iottemperaturemonitor.security.verification;

public interface EmailVerificationCodeGenerator {

    GeneratedEmailVerificationCode generate(Long userId);

    boolean matches(Long userId, String rawCode, String codeHash);
}