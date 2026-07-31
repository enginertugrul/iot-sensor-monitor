package com.enginertugrul.iottemperaturemonitor.security.verification;

public record GeneratedEmailVerificationCode(String rawCode, String codeHash) {

    public GeneratedEmailVerificationCode {
        if (rawCode == null || !rawCode.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("rawCode must contain exactly eight digits");
        }

        if (codeHash == null || codeHash.isBlank()) {
            throw new IllegalArgumentException("codeHash must not be blank");
        }
    }

    @Override
    public String toString() {
        return "GeneratedEmailVerificationCode[rawCode=[REDACTED], codeHash=[REDACTED]]";
    }
}