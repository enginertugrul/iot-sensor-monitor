package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

@Getter
@Entity
@Table(name = "daily_sensor_summaries")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailySensorSummary extends SensorSummary {

    @Column(name = "local_date", nullable = false, updatable = false)
    private LocalDate localDate;

    @Column(name = "time_zone_id", nullable = false, length = 64, updatable = false)
    private String timeZoneId;




    private DailySensorSummary(Sensor sensor, LocalDate localDate, ZoneId timeZone, Instant bucketStart, Instant bucketEnd, SensorSummaryAggregate aggregate, Instant finalizedAt) {
        super(sensor,bucketStart,bucketEnd,aggregate,finalizedAt);
        this.localDate = localDate;
        this.timeZoneId = timeZone.getId();
        validateDailyState();
    }




    public static DailySensorSummary create(Sensor sensor, LocalDate localDate, ZoneId timeZone, SensorSummaryAggregate aggregate, Instant finalizedAt) {

        LocalDate requiredLocalDate = Objects.requireNonNull(localDate,"localDate must not be null");
        ZoneId requiredTimeZone = Objects.requireNonNull(timeZone,"timeZone must not be null");

        Instant bucketStart = requiredLocalDate.atStartOfDay(requiredTimeZone).toInstant();
        Instant bucketEnd = requiredLocalDate.plusDays(1).atStartOfDay(requiredTimeZone).toInstant();

        return new DailySensorSummary(
                sensor,
                requiredLocalDate,
                requiredTimeZone,
                bucketStart,
                bucketEnd,
                aggregate,
                finalizedAt);
    }



    public ZoneId getTimeZone() {
        return ZoneId.of(timeZoneId);
    }




    @PrePersist
    @PreUpdate
    private void validateDailyState() {

        validateCommonState();

        LocalDate requiredLocalDate = Objects.requireNonNull(localDate,"localDate must not be null");

        if (timeZoneId == null || timeZoneId.isBlank()) {
            throw new IllegalArgumentException("timeZoneId must not be blank");
        }

        ZoneId timeZone = ZoneId.of(timeZoneId);

        if (!timeZoneId.equals(timeZone.getId())) {
            throw new IllegalArgumentException("timeZoneId must contain a normalized timezone identifier");
        }

        Instant expectedBucketStart = requiredLocalDate.atStartOfDay(timeZone).toInstant();
        Instant expectedBucketEnd = requiredLocalDate.plusDays(1).atStartOfDay(timeZone).toInstant();

        if (!expectedBucketStart.equals(getBucketStart()) || !expectedBucketEnd.equals(getBucketEnd())) {
            throw new IllegalArgumentException("Daily bucket boundaries must match localDate and timeZoneId");
        }
    }


}