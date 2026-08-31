package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.time.Instant;
import java.util.Objects;

public record StatisticsTierCoverageDTO(
        StatisticsResolution resolution,
        Instant retentionWindowFromInclusive,
        Instant representedFromInclusive,
        Instant representedUntilExclusive,
        StatisticsRollupProgressDTO rollupProgress
) {

    public StatisticsTierCoverageDTO {

        Objects.requireNonNull(resolution,"resolution must not be null");
        Objects.requireNonNull(retentionWindowFromInclusive, "retentionWindowFromInclusive must not be null");

        if (resolution == StatisticsResolution.AUTO) {
            throw new IllegalArgumentException("AUTO is not a storage tier");
        }

        if ((representedFromInclusive == null) != (representedUntilExclusive == null)) {
            throw new IllegalArgumentException("representedFromInclusive and representedUntilExclusive must both be null or populated");
        }

        if (representedFromInclusive != null
                && !representedFromInclusive.isBefore(representedUntilExclusive)) {

            throw new IllegalArgumentException("representedFromInclusive must be before representedUntilExclusive");
        }

        if (representedFromInclusive != null
                && representedFromInclusive.isBefore(retentionWindowFromInclusive)) {

            throw new IllegalArgumentException("Represented coverage must not begin before the retention window");
        }

        if (resolution == StatisticsResolution.RAW && rollupProgress != null) {
            throw new IllegalArgumentException("Raw coverage must not contain rollup progress");
        }

        if (resolution != StatisticsResolution.RAW && rollupProgress == null) {
            throw new IllegalArgumentException("Summary coverage must contain rollup progress");
        }
    }
}