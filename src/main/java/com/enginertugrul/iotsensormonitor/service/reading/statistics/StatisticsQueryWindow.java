package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

record StatisticsQueryWindow(
        InstantRange requested,
        InstantRange evaluated,
        Instant asOf,
        boolean endClippedToAsOf
) {

    StatisticsQueryWindow {
        Objects.requireNonNull(requested,"requested must not be null");
        Objects.requireNonNull(evaluated,"evaluated must not be null");
        Objects.requireNonNull(asOf,"asOf must not be null");

        if (!requested.covers(evaluated)) {
            throw new IllegalArgumentException("requested range must cover the evaluated range");
        }
    }

    static StatisticsQueryWindow resolve(
            Instant requestedStartInclusive,
            Instant requestedEndExclusive,
            Instant asOf,
            Duration maximumRange
    ) {
        Objects.requireNonNull(asOf,"asOf must not be null");
        Objects.requireNonNull(maximumRange,"maximumRange must not be null");

        if (requestedStartInclusive == null || requestedEndExclusive == null) {
            throw new InvalidStatisticsQueryException("startInclusive and endExclusive are required");
        }

        if (!requestedStartInclusive.isBefore(requestedEndExclusive)) {
            throw new InvalidStatisticsQueryException("startInclusive must be before endExclusive");
        }

        Duration requestedDuration = Duration.between(requestedStartInclusive, requestedEndExclusive);

        if (requestedDuration.compareTo(maximumRange) > 0) {
            throw new InvalidStatisticsQueryException("The requested range exceeds the maximum duration of " + maximumRange);
        }

        if (!requestedStartInclusive.isBefore(asOf)) {
            throw new InvalidStatisticsQueryException("startInclusive must be before the current time");
        }

        boolean endClipped = requestedEndExclusive.isAfter(asOf);
        Instant evaluatedEndExclusive = endClipped
                ? asOf
                : requestedEndExclusive;

        return new StatisticsQueryWindow(
                new InstantRange(requestedStartInclusive,requestedEndExclusive),
                new InstantRange(requestedStartInclusive,evaluatedEndExclusive),
                asOf,
                endClipped);
    }

    LocalDate firstLocalDate(ZoneId timeZone) {
        return evaluated.startInclusive().atZone(timeZone).toLocalDate();
    }

    LocalDate lastLocalDate(ZoneId timeZone) {
        return evaluated.endExclusive()
                .minusNanos(1)
                .atZone(timeZone)
                .toLocalDate();
    }
}