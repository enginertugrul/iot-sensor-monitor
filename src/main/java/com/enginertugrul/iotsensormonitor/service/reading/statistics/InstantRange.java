package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

record InstantRange(
        Instant startInclusive,
        Instant endExclusive
) {

    InstantRange {
        Objects.requireNonNull(startInclusive,"startInclusive must not be null");
        Objects.requireNonNull(endExclusive,"endExclusive must not be null");

        if (startInclusive.isAfter(endExclusive)) {
            throw new IllegalArgumentException("startInclusive must not be after endExclusive");
        }
    }

    boolean isEmpty() {
        return startInclusive.equals(endExclusive);
    }

    Duration duration() {
        return Duration.between(startInclusive,endExclusive);
    }

    boolean covers(InstantRange candidate) {
        Objects.requireNonNull(candidate,"candidate must not be null");

        return !candidate.startInclusive().isBefore(startInclusive)
                && !candidate.endExclusive().isAfter(endExclusive);
    }

    Optional<InstantRange> intersection(InstantRange other) {
        Objects.requireNonNull(other,"other must not be null");

        Instant intersectionStart = laterOf(startInclusive,other.startInclusive());
        Instant intersectionEnd = earlierOf(endExclusive,other.endExclusive());

        if (!intersectionStart.isBefore(intersectionEnd)) {
            return Optional.empty();
        }

        return Optional.of(new InstantRange(intersectionStart,intersectionEnd));
    }

    private static Instant earlierOf(Instant first,Instant second) {
        return first.isBefore(second) ? first : second;
    }

    private static Instant laterOf(Instant first,Instant second) {
        return first.isAfter(second) ? first : second;
    }
}