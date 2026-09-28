package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class StatisticsQueryWindowTest {

    private static final Instant AS_OF = Instant.parse("2026-01-16T12:00:00Z");
    private static final Instant START = AS_OF.minusSeconds(3600);
    private static final Duration MAXIMUM_RANGE = Duration.ofDays(7);



    @ParameterizedTest
    @ValueSource(longs = {-1,0})
    void preservesRequestedRangesEndingBeforeOrExactlyAtAsOf(long endOffsetNanos) {
        Instant end = AS_OF.plusNanos(endOffsetNanos);
        InstantRange expected = new InstantRange(START,end);

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(START,end,AS_OF,MAXIMUM_RANGE);

        assertThat(window.requested()).isEqualTo(expected);
        assertThat(window.evaluated()).isEqualTo(expected);
        assertThat(window.asOf()).isEqualTo(AS_OF);
        assertThat(window.endClippedToAsOf()).isFalse();
    }



    @Test
    void clipsAFutureEndWhilePreservingTheOriginalRequest() {
        Instant requestedEnd = AS_OF.plusSeconds(3600);

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(START,requestedEnd,AS_OF,MAXIMUM_RANGE);

        assertThat(window.requested()).isEqualTo(new InstantRange(START,requestedEnd));
        assertThat(window.evaluated()).isEqualTo(new InstantRange(START,AS_OF));
        assertThat(window.asOf()).isEqualTo(AS_OF);
        assertThat(window.endClippedToAsOf()).isTrue();
    }



    @Test
    void acceptsAOneNanosecondRangeImmediatelyBeforeAsOf() {
        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(AS_OF.minusNanos(1),AS_OF,AS_OF,MAXIMUM_RANGE);

        assertThat(window.evaluated().duration()).isEqualTo(Duration.ofNanos(1));
        assertThat(window.endClippedToAsOf()).isFalse();
    }



    @Test
    void acceptsExactlyTheMaximumRequestedDuration() {
        Instant start = AS_OF.minus(MAXIMUM_RANGE);

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(start,AS_OF,AS_OF,MAXIMUM_RANGE);

        assertThat(window.requested().duration()).isEqualTo(MAXIMUM_RANGE);
        assertThat(window.evaluated()).isEqualTo(window.requested());
    }



    @Test
    void rejectsARequestedDurationOneNanosecondAboveTheMaximum() {
        Instant start = AS_OF.minus(MAXIMUM_RANGE).minusNanos(1);

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(start,AS_OF,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested range exceeds the maximum duration of " + MAXIMUM_RANGE);
    }



    @Test
    void validatesTheMaximumAgainstTheRequestedRangeBeforeFutureClipping() {
        Instant start = AS_OF.minusSeconds(1);
        Instant end = AS_OF.plus(MAXIMUM_RANGE);

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(start,end,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested range exceeds the maximum duration of " + MAXIMUM_RANGE);
    }



    @Test
    void rejectsMissingRequestedEndpoints() {
        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(null,AS_OF,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive and endExclusive are required");

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(START,null,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive and endExclusive are required");

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(null,null,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive and endExclusive are required");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,-1})
    void rejectsEmptyAndReversedRequests(long endOffsetNanos) {
        Instant end = START.plusNanos(endOffsetNanos);

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(START,end,AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive must be before endExclusive");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsRequestsStartingAtOrAfterAsOf(long startOffsetNanos) {
        Instant start = AS_OF.plusNanos(startOffsetNanos);

        assertThatThrownBy(() -> StatisticsQueryWindow.resolve(start,start.plusSeconds(1),AS_OF,MAXIMUM_RANGE))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive must be before the current time");
    }



    @Test
    void requiresAsOfAndMaximumRangeWhenResolving() {
        assertThatNullPointerException()
                .isThrownBy(() -> StatisticsQueryWindow.resolve(START,AS_OF,null,MAXIMUM_RANGE))
                .withMessage("asOf must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> StatisticsQueryWindow.resolve(START,AS_OF,AS_OF,null))
                .withMessage("maximumRange must not be null");
    }



    @Test
    void constructorRequiresBothRangesAndAsOf() {
        InstantRange range = new InstantRange(START,AS_OF);

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsQueryWindow(null,range,AS_OF,false))
                .withMessage("requested must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsQueryWindow(range,null,AS_OF,false))
                .withMessage("evaluated must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsQueryWindow(range,range,null,false))
                .withMessage("asOf must not be null");
    }



    @ParameterizedTest
    @CsvSource({"-1,0","0,1"})
    void constructorRejectsAnEvaluatedRangeOutsideTheRequestedRange(long startOffset,long endOffset) {
        InstantRange requested = new InstantRange(START,AS_OF);
        InstantRange evaluated = new InstantRange(START.plusNanos(startOffset),AS_OF.plusNanos(endOffset));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryWindow(requested,evaluated,AS_OF,false))
                .withMessage("requested range must cover the evaluated range");
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-15T23:00:00Z,2026-01-16T00:00:00Z,2026-01-15,2026-01-15",
            "UTC,2026-01-15T23:59:59.999999999Z,2026-01-16T00:00:00Z,2026-01-15,2026-01-15",
            "Asia/Kathmandu,2026-01-15T18:15:00Z,2026-01-16T18:15:00Z,2026-01-16,2026-01-16",
            "Asia/Kathmandu,2026-01-15T18:15:00Z,2026-01-16T18:15:00.000000001Z,2026-01-16,2026-01-17",
            "Europe/Berlin,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z,2026-03-29,2026-03-29",
            "Europe/Berlin,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z,2026-10-25,2026-10-25"
    })
    void resolvesLocalDatesUsingHalfOpenBoundaries(String zoneId,String startText,String endText,String firstDate,String lastDate) {
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        ZoneId timeZone = ZoneId.of(zoneId);
        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(start,end,end.plusSeconds(1),MAXIMUM_RANGE);

        assertThat(window.firstLocalDate(timeZone)).isEqualTo(LocalDate.parse(firstDate));
        assertThat(window.lastLocalDate(timeZone)).isEqualTo(LocalDate.parse(lastDate));
    }



    @Test
    void localDatesUseTheClippedEndInsteadOfTheFutureRequestedEnd() {
        Instant start = Instant.parse("2026-01-15T23:00:00Z");
        Instant requestedEnd = Instant.parse("2026-01-18T00:00:00Z");
        Instant asOf = Instant.parse("2026-01-16T00:00:00Z");

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(start,requestedEnd,asOf,MAXIMUM_RANGE);

        assertThat(window.endClippedToAsOf()).isTrue();
        assertThat(window.firstLocalDate(ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026,1,15));
        assertThat(window.lastLocalDate(ZoneOffset.UTC)).isEqualTo(LocalDate.of(2026,1,15));
    }
}