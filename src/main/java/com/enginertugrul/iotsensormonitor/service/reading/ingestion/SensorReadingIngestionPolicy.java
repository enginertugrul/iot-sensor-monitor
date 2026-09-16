package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Component
public class SensorReadingIngestionPolicy {
    private final Duration maximumLateness;
    private final Duration maximumFutureSkew;
    private final Duration rawRetention;

    public SensorReadingIngestionPolicy(
            @Value("${app.sensor-data.ingestion.maximum-lateness:PT6H}") Duration maximumLateness,
            @Value("${app.sensor-data.ingestion.maximum-future-skew:PT2M}") Duration maximumFutureSkew,
            SensorDataLifecyclePolicy lifecyclePolicy) {
        this.maximumLateness = requireNonNegative(maximumLateness,"maximumLateness");
        this.maximumFutureSkew = requireNonNegative(maximumFutureSkew,"maximumFutureSkew");
        this.rawRetention = Objects.requireNonNull(lifecyclePolicy,"lifecyclePolicy must not be null").getRawRetention();
        if (this.maximumLateness.compareTo(rawRetention) >= 0) {
            throw new IllegalArgumentException("maximumLateness must be shorter than rawRetention");
        }
    }

    public Instant requireValidRecordedAt(Sensor sensor,Instant recordedAt,Instant acceptedAt) {
        Objects.requireNonNull(sensor,"sensor must not be null");
        Objects.requireNonNull(acceptedAt,"acceptedAt must not be null");
        if (recordedAt == null) {
            throw new InvalidSensorReadingException("recordedAt must not be null");
        }

        Instant normalizedRecordedAt = recordedAt.truncatedTo(ChronoUnit.MICROS);
        if (normalizedRecordedAt.isBefore(sensor.getCreatedAt())) {
            throw new InvalidSensorReadingException("recordedAt must not be before sensor creation");
        }
        if (Duration.between(normalizedRecordedAt,acceptedAt).compareTo(maximumLateness) > 0) {
            throw new InvalidSensorReadingException("recordedAt exceeds maximum lateness");
        }
        if (Duration.between(acceptedAt,normalizedRecordedAt).compareTo(maximumFutureSkew) > 0) {
            throw new InvalidSensorReadingException("recordedAt exceeds maximum future skew");
        }

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        Instant localDayStart = normalizedRecordedAt.atZone(timeZone).toLocalDate().atStartOfDay(timeZone).toInstant();
        Instant requiredRawStart = localDayStart.truncatedTo(ChronoUnit.HOURS);
        Instant sensorCreationHour = sensor.getCreatedAt().truncatedTo(ChronoUnit.HOURS);
        if (requiredRawStart.isBefore(sensorCreationHour)) {
            requiredRawStart = sensorCreationHour;
        }
        Instant rawRetentionBoundary = acceptedAt.minus(rawRetention).truncatedTo(ChronoUnit.HOURS);
        if (requiredRawStart.isBefore(rawRetentionBoundary)) {
            throw new InvalidSensorReadingException("recordedAt requires source data outside raw retention");
        }
        return normalizedRecordedAt;
    }

    private static Duration requireNonNegative(Duration value,String fieldName) {
        Objects.requireNonNull(value,fieldName + " must not be null");
        if (value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value;
    }
}