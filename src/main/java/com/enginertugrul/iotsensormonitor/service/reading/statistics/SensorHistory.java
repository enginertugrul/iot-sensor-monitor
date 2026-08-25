package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

record SensorHistory(Instant sensorCreatedAt, Optional<Instant> firstReadingAt) {

    SensorHistory {
        Objects.requireNonNull(sensorCreatedAt,"sensorCreatedAt must not be null");
        firstReadingAt = Objects.requireNonNull(firstReadingAt,"firstReadingAt must not be null");

        firstReadingAt.ifPresent(firstReading -> {
            if (firstReading.isBefore(sensorCreatedAt)) {
                throw new IllegalStateException("firstReadingAt must not be before sensorCreatedAt");
            }
        });
    }

    static SensorHistory from(Sensor sensor) {
        Objects.requireNonNull(sensor,"sensor must not be null");

        return new SensorHistory(
                sensor.getCreatedAt(),
                Optional.ofNullable(sensor.getFirstReadingAt()));
    }

    boolean hasReadings() {
        return firstReadingAt.isPresent();
    }

    boolean isKnownEmptyUntil(Instant endExclusive) {
        Objects.requireNonNull(endExclusive,"endExclusive must not be null");

        return firstReadingAt
                .map(firstReading -> !endExclusive.isAfter(firstReading))
                .orElse(true);
    }

    Optional<Instant> firstDataAtOrAfter(Instant startInclusive) {
        Objects.requireNonNull(startInclusive,"startInclusive must not be null");

        return firstReadingAt.map(firstReading ->
                firstReading.isAfter(startInclusive) ? firstReading : startInclusive);
    }

    Optional<Instant> hourlyCoverageOrigin() {
        return firstReadingAt.map(firstReading ->
                firstReading.truncatedTo(ChronoUnit.HOURS));
    }

    Optional<Instant> dailyCoverageOrigin(ZoneId timeZone) {
        Objects.requireNonNull(timeZone,"timeZone must not be null");

        return firstReadingAt.map(firstReading ->
                firstReading.atZone(timeZone)
                        .toLocalDate()
                        .atStartOfDay(timeZone)
                        .toInstant());
    }
}