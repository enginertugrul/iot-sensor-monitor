package com.enginertugrul.iottemperaturemonitor.security.onetimecode;

public record GeneratedOneTimeCode(String rawCode, String codeHash) {

    public GeneratedOneTimeCode {
        if (rawCode == null || !rawCode.matches("[0-9]{8}")) {
            throw new IllegalArgumentException("rawCode must contain exactly eight digits");
        }

        if (codeHash == null || codeHash.isBlank()) {
            throw new IllegalArgumentException("codeHash must not be blank");
        }
    }

    @Override
    public String toString() {
        return "GeneratedOneTimeCode[rawCode=[REDACTED], codeHash=[REDACTED]]";
    }
}