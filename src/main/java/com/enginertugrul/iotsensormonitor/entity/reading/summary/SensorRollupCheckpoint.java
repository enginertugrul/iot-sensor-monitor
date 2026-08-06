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
@Table(name = "sensor_rollup_checkpoints")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorRollupCheckpoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false, updatable = false)
    private Sensor sensor;

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 30, updatable = false)
    private RollupStage stage;

    @Column(name = "coverage_started_at", nullable = false, updatable = false)
    private Instant coverageStartedAt;

    @Column(name = "covered_until", nullable = false)
    private Instant coveredUntil;

    @Column(name = "last_attempted_bucket_start")
    private Instant lastAttemptedBucketStart;

    @Column(name = "last_attempted_at")
    private Instant lastAttemptedAt;

    @Column(name = "last_successful_bucket_start")
    private Instant lastSuccessfulBucketStart;

    @Column(name = "last_successful_bucket_end")
    private Instant lastSuccessfulBucketEnd;

    @Column(name = "last_successful_at")
    private Instant lastSuccessfulAt;

    @Column(name = "last_advanced_at")
    private Instant lastAdvancedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    private SensorRollupCheckpoint(Sensor sensor, RollupStage stage, Instant coverageStartedAt, Instant initializedAt) {
        this.sensor = sensor;
        this.stage = stage;
        this.coverageStartedAt = coverageStartedAt;
        this.coveredUntil = coverageStartedAt;
        this.createdAt = initializedAt;
        this.updatedAt = initializedAt;
    }





    public static SensorRollupCheckpoint initialize(Sensor sensor, RollupStage stage, Instant coverageStartedAt, Instant initializedAt) {
        SensorRollupCheckpoint checkpoint = new SensorRollupCheckpoint(
                Objects.requireNonNull(sensor,"sensor must not be null"),
                Objects.requireNonNull(stage,"stage must not be null"),
                Objects.requireNonNull(coverageStartedAt,"coverageStartedAt must not be null"),
                Objects.requireNonNull(initializedAt,"initializedAt must not be null"));

        checkpoint.validateCheckpointState();
        return checkpoint;
    }





    public void recordAttempt(Instant bucketStart, Instant attemptedAt) {
        Instant requiredBucketStart = Objects.requireNonNull(bucketStart,"bucketStart must not be null");
        Instant requiredAttemptedAt = Objects.requireNonNull(attemptedAt,"attemptedAt must not be null");

        if (!requiredBucketStart.equals(coveredUntil)) {
            throw new IllegalArgumentException("Only the next contiguous bucket may be attempted");
        }

        if (requiredAttemptedAt.isBefore(requiredBucketStart)) {
            throw new IllegalArgumentException("attemptedAt must not be before bucketStart");
        }

        requireStageBoundary(requiredBucketStart,"bucketStart");
        touch(requiredAttemptedAt);

        this.lastAttemptedBucketStart = requiredBucketStart;
        this.lastAttemptedAt = requiredAttemptedAt;
        validateCheckpointState();
    }




    public void advanceContiguously(Instant bucketStart, Instant bucketEnd, Instant successfulAt) {
        Instant requiredBucketStart = Objects.requireNonNull(bucketStart,"bucketStart must not be null");
        Instant requiredBucketEnd = Objects.requireNonNull(bucketEnd,"bucketEnd must not be null");
        Instant requiredSuccessfulAt = Objects.requireNonNull(successfulAt,"successfulAt must not be null");

        if (!requiredBucketStart.equals(coveredUntil)) {
            throw new IllegalArgumentException("Checkpoint coverage may only advance from coveredUntil");
        }

        if (!requiredBucketStart.isBefore(requiredBucketEnd)) {
            throw new IllegalArgumentException("bucketStart must be before bucketEnd");
        }

        if (!requiredBucketStart.equals(lastAttemptedBucketStart) || lastAttemptedAt == null) {
            throw new IllegalStateException("The bucket must be recorded as attempted before it succeeds");
        }

        if (requiredSuccessfulAt.isBefore(lastAttemptedAt) || requiredSuccessfulAt.isBefore(requiredBucketEnd)) {
            throw new IllegalArgumentException("successfulAt must not be before the attempt or bucket end");
        }

        requireStageBucket(requiredBucketStart,requiredBucketEnd);
        touch(requiredSuccessfulAt);

        this.coveredUntil = requiredBucketEnd;
        this.lastSuccessfulBucketStart = requiredBucketStart;
        this.lastSuccessfulBucketEnd = requiredBucketEnd;
        this.lastSuccessfulAt = requiredSuccessfulAt;
        this.lastAdvancedAt = requiredSuccessfulAt;

        validateCheckpointState();
    }





    @PrePersist
    @PreUpdate
    private void validateCheckpointState() {

        Objects.requireNonNull(sensor,"sensor must not be null");
        Objects.requireNonNull(stage,"stage must not be null");

        Instant requiredCoverageStart = Objects.requireNonNull(coverageStartedAt, "coverageStartedAt must not be null");

        Instant requiredCoveredUntil = Objects.requireNonNull(coveredUntil, "coveredUntil must not be null");

        Instant requiredCreatedAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        Instant requiredUpdatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");

        if (requiredCoverageStart.isAfter(requiredCoveredUntil)) {
            throw new IllegalArgumentException("coverageStartedAt must not be after coveredUntil");
        }

        if (requiredCreatedAt.isBefore(requiredCoverageStart)) {
            throw new IllegalArgumentException("createdAt must not be before coverageStartedAt");
        }

        if (requiredCreatedAt.isAfter(requiredUpdatedAt)) {
            throw new IllegalArgumentException("createdAt must not be after updatedAt");
        }

        requireStageBoundary(requiredCoverageStart,"coverageStartedAt");
        requireStageBoundary(requiredCoveredUntil,"coveredUntil");
        validateAttemptShape(requiredCoverageStart,requiredCoveredUntil);
        validateSuccessShape(requiredCoverageStart,requiredCoveredUntil);
    }




    private void validateAttemptShape(Instant coverageStart, Instant coverageEnd) {
        boolean hasAttemptStart = lastAttemptedBucketStart != null;
        boolean hasAttemptTime = lastAttemptedAt != null;

        if (hasAttemptStart != hasAttemptTime) {
            throw new IllegalArgumentException("Last-attempt fields must either both be null or both be populated");
        }

        if (!hasAttemptStart) {
            return;
        }

        if (lastAttemptedBucketStart.isBefore(coverageStart)
                || lastAttemptedBucketStart.isAfter(coverageEnd)) {
            throw new IllegalArgumentException("lastAttemptedBucketStart must be inside checkpoint coverage");
        }

        requireStageBoundary(lastAttemptedBucketStart,"lastAttemptedBucketStart");
        requireOperationalTimestamp(lastAttemptedAt,"lastAttemptedAt");
    }




    private void validateSuccessShape(Instant coverageStart, Instant coverageEnd) {

        boolean hasSuccessStart = lastSuccessfulBucketStart != null;
        boolean hasSuccessEnd = lastSuccessfulBucketEnd != null;
        boolean hasSuccessTime = lastSuccessfulAt != null;
        boolean hasAdvancedTime = lastAdvancedAt != null;

        if (!(hasSuccessStart == hasSuccessEnd
                && hasSuccessEnd == hasSuccessTime
                && hasSuccessTime == hasAdvancedTime)) {
            throw new IllegalArgumentException("Last-success fields must either all be null or all be populated");
        }

        if (!hasSuccessStart) {
            return;
        }

        if (!lastSuccessfulBucketStart.isBefore(lastSuccessfulBucketEnd)) {
            throw new IllegalArgumentException("Last successful bucket start must be before its end");
        }

        if (lastSuccessfulBucketStart.isBefore(coverageStart)
                || lastSuccessfulBucketEnd.isAfter(coverageEnd)) {
            throw new IllegalArgumentException("Last successful bucket must be inside checkpoint coverage");
        }

        if (lastSuccessfulAt.isBefore(lastSuccessfulBucketEnd)) {
            throw new IllegalArgumentException("lastSuccessfulAt must not be before the successful bucket end");
        }

        requireStageBucket(lastSuccessfulBucketStart,lastSuccessfulBucketEnd);
        requireOperationalTimestamp(lastSuccessfulAt,"lastSuccessfulAt");
        requireOperationalTimestamp(lastAdvancedAt,"lastAdvancedAt");
    }





    private void requireStageBucket(Instant bucketStart, Instant bucketEnd) {
        requireStageBoundary(bucketStart,"bucketStart");
        requireStageBoundary(bucketEnd,"bucketEnd");

        if (stage == RollupStage.RAW_TO_HOURLY
                && !bucketEnd.equals(bucketStart.plus(1,ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("Raw-to-hourly coverage must advance one UTC hour at a time");
        }
    }




    private void requireStageBoundary(Instant value, String fieldName) {
        if (stage == RollupStage.RAW_TO_HOURLY
                && !value.equals(value.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException(fieldName + " must be aligned to a UTC hour");
        }
    }




    private void requireOperationalTimestamp(Instant timestamp, String fieldName) {
        if (timestamp.isBefore(createdAt) || timestamp.isAfter(updatedAt)) {
            throw new IllegalArgumentException(fieldName + " must be between createdAt and updatedAt");
        }
    }




    private void touch(Instant timestamp) {
        if (timestamp.isBefore(createdAt) || timestamp.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Checkpoint operational timestamps must not move backwards");
        }

        this.updatedAt = timestamp;
    }
}