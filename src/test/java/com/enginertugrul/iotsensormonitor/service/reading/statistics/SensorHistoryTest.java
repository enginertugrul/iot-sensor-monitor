package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;



class SensorHistoryTest {

    private static final Instant FIRST_READING = CREATED_AT.plusSeconds(90);



    @Test
    void mapsASensorWithoutReadingsToEmptyHistory() {
        SensorHistory history = SensorHistory.from(sensor(SensorType.TEMPERATURE));

        assertThat(history.sensorCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(history.firstReadingAt()).isEmpty();
        assertThat(history.hasReadings()).isFalse();
        assertThat(history.firstDataAtOrAfter(CREATED_AT)).isEmpty();
        assertThat(history.hourlyCoverageOrigin()).isEmpty();
        assertThat(history.dailyCoverageOrigin(ZoneOffset.UTC)).isEmpty();
    }



    @Test
    void mapsSensorCreationAndFirstReadingIntoASnapshot() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        sensor.recordFirstReading(FIRST_READING,FIRST_READING);

        SensorHistory history = SensorHistory.from(sensor);
        sensor.recordFirstReading(CREATED_AT,FIRST_READING.plusSeconds(1));

        assertThat(history.sensorCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(history.firstReadingAt()).contains(FIRST_READING);
        assertThat(history.hasReadings()).isTrue();
        assertThat(SensorHistory.from(sensor).firstReadingAt()).contains(CREATED_AT);
    }



    @Test
    void acceptsTheFirstReadingExactlyAtSensorCreation() {
        SensorHistory history = new SensorHistory(CREATED_AT,Optional.of(CREATED_AT));

        assertThat(history.hasReadings()).isTrue();
        assertThat(history.firstReadingAt()).contains(CREATED_AT);
    }



    @Test
    void rejectsTheFirstReadingBeforeSensorCreation() {
        assertThatIllegalStateException()
                .isThrownBy(() -> new SensorHistory(CREATED_AT,Optional.of(CREATED_AT.minusNanos(1))))
                .withMessage("firstReadingAt must not be before sensorCreatedAt");
    }



    @Test
    void rejectsMissingConstructionInputs() {
        assertThatNullPointerException()
                .isThrownBy(() -> new SensorHistory(null,Optional.empty()))
                .withMessage("sensorCreatedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new SensorHistory(CREATED_AT,null))
                .withMessage("firstReadingAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorHistory.from(null))
                .withMessage("sensor must not be null");
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,0,1,86400000000000L})
    void historyWithoutReadingsIsKnownEmptyForEveryRequestedEnd(long offsetNanos) {
        SensorHistory history = new SensorHistory(CREATED_AT,Optional.empty());

        assertThat(history.isKnownEmptyUntil(CREATED_AT.plusNanos(offsetNanos))).isTrue();
    }



    @ParameterizedTest
    @CsvSource({"-1,true","0,true","1,false"})
    void knownEmptyHistoryEndsAtTheFirstReadingExclusiveBoundary(long offsetNanos,boolean expected) {
        SensorHistory history = new SensorHistory(CREATED_AT,Optional.of(FIRST_READING));

        assertThat(history.isKnownEmptyUntil(FIRST_READING.plusNanos(offsetNanos))).isEqualTo(expected);
    }



    @ParameterizedTest
    @CsvSource({"-1,0","0,0","1,1"})
    void firstPossibleDataDoesNotPrecedeEitherTheFirstReadingOrRequestedStart(long startOffset,long expectedOffset) {
        SensorHistory history = new SensorHistory(CREATED_AT,Optional.of(FIRST_READING));

        assertThat(history.firstDataAtOrAfter(FIRST_READING.plusNanos(startOffset)))
                .contains(FIRST_READING.plusNanos(expectedOffset));
    }



    @ParameterizedTest
    @CsvSource({
            "2026-01-15T12:00:00Z,2026-01-15T12:00:00Z",
            "2026-01-15T12:00:00.000000001Z,2026-01-15T12:00:00Z",
            "2026-01-15T12:59:59.999999999Z,2026-01-15T12:00:00Z",
            "2026-01-15T13:00:00Z,2026-01-15T13:00:00Z"
    })
    void hourlyCoverageStartsAtTheContainingUtcHour(String firstReading,String expectedOrigin) {
        SensorHistory history = historyAt(Instant.parse(firstReading));

        assertThat(history.hourlyCoverageOrigin()).contains(Instant.parse(expectedOrigin));
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-16T00:00:00Z,2026-01-16T00:00:00Z",
            "Europe/Istanbul,2026-01-15T21:30:00Z,2026-01-15T21:00:00Z",
            "Asia/Kathmandu,2026-01-15T18:30:00Z,2026-01-15T18:15:00Z",
            "Europe/Berlin,2026-03-29T01:30:00Z,2026-03-28T23:00:00Z",
            "Europe/Berlin,2026-10-25T01:30:00Z,2026-10-24T22:00:00Z"
    })
    void dailyCoverageStartsAtSensorLocalMidnight(String zoneId,String firstReading,String expectedOrigin) {
        SensorHistory history = historyAt(Instant.parse(firstReading));

        assertThat(history.dailyCoverageOrigin(ZoneId.of(zoneId))).contains(Instant.parse(expectedOrigin));
    }



    @Test
    void rejectsMissingQueryInputsEvenWhenHistoryIsEmpty() {
        SensorHistory history = new SensorHistory(CREATED_AT,Optional.empty());

        assertThatNullPointerException()
                .isThrownBy(() -> history.isKnownEmptyUntil(null))
                .withMessage("endExclusive must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> history.firstDataAtOrAfter(null))
                .withMessage("startInclusive must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> history.dailyCoverageOrigin(null))
                .withMessage("timeZone must not be null");
    }



    private static SensorHistory historyAt(Instant firstReading) {
        return new SensorHistory(firstReading.minusSeconds(1),Optional.of(firstReading));
    }
}