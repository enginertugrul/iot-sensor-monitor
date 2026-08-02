package com.enginertugrul.iotsensormonitor.security.ingestion;

public interface SensorIngestionTokenGenerator {

    GeneratedSensorIngestionToken generate();

    String hash(String rawToken);

}
