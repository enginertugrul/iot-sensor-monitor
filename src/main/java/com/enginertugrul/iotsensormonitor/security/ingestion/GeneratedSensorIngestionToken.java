package com.enginertugrul.iotsensormonitor.security.ingestion;

public record GeneratedSensorIngestionToken(String rawToken, String tokenHash) {
    @Override
    public String toString() {
        return "GeneratedSensorIngestionToken[rawToken=[REDACTED], tokenHash=[REDACTED]]";
    }
}
