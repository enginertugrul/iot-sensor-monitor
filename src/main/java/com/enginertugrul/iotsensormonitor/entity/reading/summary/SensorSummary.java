package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.ReadingValueKind;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.util.Objects;

@Getter
@MappedSuperclass
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public abstract class SensorSummary {

    private static final MathContext AVERAGE_MATH_CONTEXT = MathContext.DECIMAL128;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false, updatable = false)
    private Sensor sensor;

    @Column(name = "bucket_start", nullable = false, updatable = false)
    private Instant bucketStart;

    @Column(name = "bucket_end", nullable = false, updatable = false)
    private Instant bucketEnd;

    @Column(name = "source_sample_count", nullable = false)
    private long sourceSampleCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", length = 20)
    private MeasurementUnit unit;

    @Column(name = "numeric_sum", columnDefinition = "NUMERIC")
    private BigDecimal numericSum;

    @Column(name = "numeric_minimum")
    private Double numericMinimum;

    @Column(name = "numeric_maximum")
    private Double numericMaximum;

    @Column(name = "true_sample_count")
    private Long trueSampleCount;

    @Column(name = "finalized_at", nullable = false, updatable = false)
    private Instant finalizedAt;

    @Column(name = "refreshed_at", nullable = false)
    private Instant refreshedAt;




    protected SensorSummary(Sensor sensor, Instant bucketStart, Instant bucketEnd, SensorSummaryAggregate aggregate, Instant finalizedAt) {
        this.sensor = Objects.requireNonNull(sensor,"sensor must not be null");
        this.bucketStart = Objects.requireNonNull(bucketStart,"bucketStart must not be null");
        this.bucketEnd = Objects.requireNonNull(bucketEnd,"bucketEnd must not be null");
        this.finalizedAt = Objects.requireNonNull(finalizedAt,"finalizedAt must not be null");
        this.refreshedAt = this.finalizedAt;

        applyAggregate(Objects.requireNonNull(aggregate,"aggregate must not be null"));
        validateCommonState();
    }






    public void refresh(SensorSummaryAggregate aggregate, Instant refreshedAt) {

        SensorSummaryAggregate requiredAggregate = Objects.requireNonNull(aggregate,"aggregate must not be null");
        Instant requiredRefreshedAt = Objects.requireNonNull(refreshedAt,"refreshedAt must not be null");


        if (requiredRefreshedAt.isBefore(this.refreshedAt)) {
            throw new IllegalArgumentException("refreshedAt must not move backwards");
        }

        applyAggregate(requiredAggregate);
        this.refreshedAt = requiredRefreshedAt;
        validateCommonState();
    }




    public boolean hasSamples() {
        return sourceSampleCount > 0;
    }



    public boolean isNumeric() {
        return sensor.getReadingValueKind() == ReadingValueKind.NUMERIC;
    }



    public boolean isBoolean() {
        return sensor.getReadingValueKind() == ReadingValueKind.BOOLEAN;
    }



    public SensorSummaryAggregate toAggregate() {

        return SensorSummaryAggregate.restore(
                sensor.getReadingValueKind(),
                sourceSampleCount,
                unit,
                numericSum,
                numericMinimum,
                numericMaximum,
                trueSampleCount);
    }




    public BigDecimal getNumericAverage() {
        if (!isNumeric()) {
            throw new IllegalStateException("Numeric average is only available for numeric summaries");
        }

        if (!hasSamples()) {
            return null;
        }

        return numericSum.divide(BigDecimal.valueOf(sourceSampleCount),AVERAGE_MATH_CONTEXT);
    }




    public long getFalseSampleCount() {
        if (!isBoolean()) {
            throw new IllegalStateException("False sample count is only available for boolean summaries");
        }

        return sourceSampleCount - trueSampleCount;
    }





    protected void validateCommonState() {
        Instant requiredBucketStart = Objects.requireNonNull(bucketStart,"bucketStart must not be null");
        Instant requiredBucketEnd = Objects.requireNonNull(bucketEnd,"bucketEnd must not be null");
        Instant requiredFinalizedAt = Objects.requireNonNull(finalizedAt,"finalizedAt must not be null");
        Instant requiredRefreshedAt = Objects.requireNonNull(refreshedAt,"refreshedAt must not be null");

        if (!requiredBucketStart.isBefore(requiredBucketEnd)) {
            throw new IllegalArgumentException("bucketStart must be before bucketEnd");
        }

        if (requiredFinalizedAt.isBefore(requiredBucketEnd)) {
            throw new IllegalArgumentException("finalizedAt must not be before bucketEnd");
        }

        if (requiredRefreshedAt.isBefore(requiredFinalizedAt)) {
            throw new IllegalArgumentException("refreshedAt must not be before finalizedAt");
        }

    }





    private void applyAggregate(SensorSummaryAggregate aggregate) {
        aggregate.requireCompatibleWith(sensor.getType());
        this.sourceSampleCount = aggregate.getSourceSampleCount();
        this.unit = aggregate.getUnit();
        this.numericSum = aggregate.getNumericSum();
        this.numericMinimum = aggregate.getNumericMinimum();
        this.numericMaximum = aggregate.getNumericMaximum();
        this.trueSampleCount = aggregate.getTrueSampleCount();
    }

}