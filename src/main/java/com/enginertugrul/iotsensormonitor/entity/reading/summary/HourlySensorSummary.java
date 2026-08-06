package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Getter
@Entity
@Table(name = "hourly_sensor_summaries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HourlySensorSummary extends SensorSummary {



    private HourlySensorSummary(Sensor sensor, Instant bucketStart, SensorSummaryAggregate aggregate, Instant finalizedAt) {
        super(sensor,bucketStart,bucketStart.plus(1,ChronoUnit.HOURS),aggregate,finalizedAt);
        validateHourlyState();
    }


    public static HourlySensorSummary create(Sensor sensor, Instant bucketStart, SensorSummaryAggregate aggregate, Instant finalizedAt) {
        return new HourlySensorSummary(
                sensor,
                requireUtcHourBoundary(bucketStart),
                aggregate,
                finalizedAt);
    }





    @PrePersist
    @PreUpdate
    private void validateHourlyState() {
        validateCommonState();

        Instant bucketStart = requireUtcHourBoundary(getBucketStart());
        Instant expectedBucketEnd = bucketStart.plus(1,ChronoUnit.HOURS);

        if (!expectedBucketEnd.equals(getBucketEnd())) {
            throw new IllegalArgumentException("Hourly bucketEnd must be exactly one hour after bucketStart");
        }
    }



    private static Instant requireUtcHourBoundary(Instant value) {
        Instant instant = Objects.requireNonNull(value,"bucketStart must not be null");

        if (!instant.equals(instant.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("bucketStart must be aligned to a UTC hour");
        }

        return instant;
    }


}