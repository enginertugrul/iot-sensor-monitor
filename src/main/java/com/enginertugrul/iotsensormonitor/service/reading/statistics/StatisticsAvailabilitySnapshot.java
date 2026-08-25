package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

record StatisticsTierRetention(Instant expirationCutoff, InstantRange retentionWindow) {

    StatisticsTierRetention {
        Objects.requireNonNull(expirationCutoff,"expirationCutoff must not be null");
        Objects.requireNonNull(retentionWindow,"retentionWindow must not be null");
    }
}


record StatisticsRollupProgress(
        Optional<InstantRange> verifiedCoverage,
        Instant rollupDueUntilExclusive,
        Duration lag
) {

    StatisticsRollupProgress {

        verifiedCoverage = Objects.requireNonNull(verifiedCoverage, "verifiedCoverage must not be null");

        Objects.requireNonNull(rollupDueUntilExclusive, "rollupDueUntilExclusive must not be null");

        Objects.requireNonNull(lag,"lag must not be null");

        if (lag.isNegative()) {
            throw new IllegalArgumentException("lag must not be negative");
        }

    }

    boolean isDelayed() {
        return !lag.isZero();
    }

    boolean verifies(InstantRange sourceRange) {
        return verifiedCoverage
                .map(coverage -> coverage.covers(sourceRange))
                .orElse(false);
    }
}


record StatisticsTierAvailability(
        StatisticsResolution resolution,
        StatisticsTierRetention retention,
        Optional<InstantRange> representedCoverage,
        Optional<StatisticsRollupProgress> rollupProgress
) {

    StatisticsTierAvailability {

        Objects.requireNonNull(resolution,"resolution must not be null");
        Objects.requireNonNull(retention,"retention must not be null");

        representedCoverage = Objects.requireNonNull(representedCoverage, "representedCoverage must not be null");
        rollupProgress = Objects.requireNonNull(rollupProgress, "rollupProgress must not be null");

        if (resolution == StatisticsResolution.AUTO) {
            throw new IllegalArgumentException("AUTO is not a storage tier");
        }

        if (resolution == StatisticsResolution.RAW && rollupProgress.isPresent()) {
            throw new IllegalArgumentException("Raw availability must not contain rollup progress");
        }

        if (resolution != StatisticsResolution.RAW && rollupProgress.isEmpty()) {
            throw new IllegalArgumentException("Summary availability requires rollup progress");
        }
    }

    StatisticsRollupProgress requireRollupProgress() {
        return rollupProgress.orElseThrow(() -> new IllegalStateException(resolution + " has no rollup progress"));
    }

    boolean verifies(InstantRange sourceRange) {
        return rollupProgress
                .map(progress -> progress.verifies(sourceRange))
                .orElse(false);
    }
}


record StatisticsAvailabilitySnapshot(
        SensorHistory history,
        StatisticsTierAvailability raw,
        StatisticsTierAvailability hourly,
        StatisticsTierAvailability daily
) {

    StatisticsAvailabilitySnapshot {
        Objects.requireNonNull(history,"history must not be null");
        Objects.requireNonNull(raw,"raw must not be null");
        Objects.requireNonNull(hourly,"hourly must not be null");
        Objects.requireNonNull(daily,"daily must not be null");
    }
}