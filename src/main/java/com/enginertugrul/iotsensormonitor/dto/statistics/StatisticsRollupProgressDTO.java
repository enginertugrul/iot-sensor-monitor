package com.enginertugrul.iotsensormonitor.dto.statistics;

import java.time.Instant;
import java.util.Objects;

public record StatisticsRollupProgressDTO(
        Instant verifiedFromInclusive,
        Instant safeThroughExclusive,
        Instant rollupDueUntilExclusive,
        long lagSeconds,
        boolean delayed
) {

    public StatisticsRollupProgressDTO {
        Objects.requireNonNull(rollupDueUntilExclusive,"rollupDueUntilExclusive must not be null");

        if ((verifiedFromInclusive == null) != (safeThroughExclusive == null)) {
            throw new IllegalArgumentException(
                    "verifiedFromInclusive and safeThroughExclusive must both be null or populated");
        }

        if (verifiedFromInclusive != null && verifiedFromInclusive.isAfter(safeThroughExclusive)) {
            throw new IllegalArgumentException(
                    "verifiedFromInclusive must not be after safeThroughExclusive");
        }

        if (lagSeconds < 0) {
            throw new IllegalArgumentException("lagSeconds must not be negative");
        }

        if (delayed != (lagSeconds > 0)) {
            throw new IllegalArgumentException("delayed must match lagSeconds");
        }
    }
}