package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class SensorReadingIngestionPolicyTest {

    private static final Instant ACCEPTED_AT = CREATED_AT;
    private static final Duration MAXIMUM_LATENESS = Duration.ofHours(6);
    private static final Duration MAXIMUM_FUTURE_SKEW = Duration.ofMinutes(2);
    private static final Duration RAW_RETENTION = Duration.ofDays(30);

    private final SensorReadingIngestionPolicy policy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,RAW_RETENTION);



    @Test
    void requiresBothDurationsAndLifecyclePolicy() {
        assertThatThrownBy(() -> newPolicy(null,MAXIMUM_FUTURE_SKEW,RAW_RETENTION))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("maximumLateness must not be null");

        assertThatThrownBy(() -> newPolicy(MAXIMUM_LATENESS,null,RAW_RETENTION))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("maximumFutureSkew must not be null");

        assertThatThrownBy(() -> new SensorReadingIngestionPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("lifecyclePolicy must not be null");
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,-1_000_000_000})
    void rejectsNegativeLatenessAndFutureSkew(long nanos) {
        Duration negative = Duration.ofNanos(nanos);

        assertThatThrownBy(() -> newPolicy(negative,MAXIMUM_FUTURE_SKEW,RAW_RETENTION))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumLateness must not be negative");

        assertThatThrownBy(() -> newPolicy(MAXIMUM_LATENESS,negative,RAW_RETENTION))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumFutureSkew must not be negative");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsLatenessEqualToOrLongerThanRawRetention(long additionalNanos) {
        Duration maximumLateness = RAW_RETENTION.plusNanos(additionalNanos);

        assertThatThrownBy(() -> newPolicy(maximumLateness,MAXIMUM_FUTURE_SKEW,RAW_RETENTION))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("maximumLateness must be shorter than rawRetention");
    }



    @Test
    void acceptsLatenessOneNanosecondShorterThanRawRetention() {
        SensorReadingIngestionPolicy boundaryPolicy = newPolicy(RAW_RETENTION.minusNanos(1),MAXIMUM_FUTURE_SKEW,RAW_RETENTION);

        assertThat(boundaryPolicy.requireValidRecordedAt(existingSensor(),ACCEPTED_AT,ACCEPTED_AT))
                .isEqualTo(ACCEPTED_AT);
    }



    @Test
    void supportsZeroLatenessAndZeroFutureSkew() {
        SensorReadingIngestionPolicy strictPolicy = newPolicy(Duration.ZERO,Duration.ZERO,RAW_RETENTION);
        Sensor sensor = existingSensor();

        assertThat(strictPolicy.requireValidRecordedAt(sensor,ACCEPTED_AT,ACCEPTED_AT)).isEqualTo(ACCEPTED_AT);

        assertThatThrownBy(() -> strictPolicy.requireValidRecordedAt(sensor,ACCEPTED_AT.minusNanos(1000),ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt exceeds maximum lateness");

        assertThatThrownBy(() -> strictPolicy.requireValidRecordedAt(sensor,ACCEPTED_AT.plusNanos(1000),ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt exceeds maximum future skew");
    }



    @Test
    void requiresSensorAndAcceptanceTimestamp() {
        Sensor sensor = existingSensor();

        assertThatThrownBy(() -> policy.requireValidRecordedAt(null,ACCEPTED_AT,ACCEPTED_AT))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("sensor must not be null");

        assertThatThrownBy(() -> policy.requireValidRecordedAt(sensor,ACCEPTED_AT,null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("acceptedAt must not be null");
    }



    @Test
    void rejectsMissingRecordedTimestampAsInvalidReading() {
        assertThatThrownBy(() -> policy.requireValidRecordedAt(existingSensor(),null,ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt must not be null");
    }



    @ParameterizedTest
    @CsvSource({
            "0,0",
            "1,0",
            "999,0",
            "1000,1000",
            "123000000,123000000",
            "123456789,123456000",
            "999999999,999999000"
    })
    void truncatesRecordedTimestampToMicrosecondsWithoutRounding(long suppliedNanos,long expectedNanos) {
        Sensor sensor = existingSensor();
        Instant recordedAt = ACCEPTED_AT.plusNanos(suppliedNanos);
        Instant expected = ACCEPTED_AT.plusNanos(expectedNanos);

        Instant result = policy.requireValidRecordedAt(sensor,recordedAt,ACCEPTED_AT);

        assertThat(result).isEqualTo(expected);
        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.getUpdatedAt()).isEqualTo(sensor.getCreatedAt());
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1,999})
    void acceptsNormalizedTimestampEqualToSensorCreation(long additionalNanos) {
        Instant createdAt = ACCEPTED_AT.minusSeconds(1).plusNanos(123456000);
        Sensor sensor = sensorInZone("UTC",createdAt);

        assertThat(policy.requireValidRecordedAt(sensor,createdAt.plusNanos(additionalNanos),ACCEPTED_AT))
                .isEqualTo(createdAt);
    }



    @Test
    void rejectsTimestampOneNanosecondBeforeSensorCreation() {
        Instant createdAt = ACCEPTED_AT.minusSeconds(1).plusNanos(123456000);
        Sensor sensor = sensorInZone("UTC",createdAt);

        assertThatThrownBy(() -> policy.requireValidRecordedAt(sensor,createdAt.minusNanos(1),ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt must not be before sensor creation");
    }



    @ParameterizedTest
    @CsvSource({"0,0","999,0","1000,1000"})
    void acceptsExactLatenessBoundaryAndTimestampsInsideIt(long suppliedNanos,long expectedNanos) {
        Instant earliest = ACCEPTED_AT.minus(MAXIMUM_LATENESS);

        assertThat(policy.requireValidRecordedAt(existingSensor(),earliest.plusNanos(suppliedNanos),ACCEPTED_AT))
                .isEqualTo(earliest.plusNanos(expectedNanos));
    }



    @Test
    void rejectsTimestampOneNanosecondBeyondLatenessBoundary() {
        Instant recordedAt = ACCEPTED_AT.minus(MAXIMUM_LATENESS).minusNanos(1);

        assertThatThrownBy(() -> policy.requireValidRecordedAt(existingSensor(),recordedAt,ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt exceeds maximum lateness");
    }



    @Test
    void checksLatenessUsingNormalizedRecordedTimeAndFullAcceptancePrecision() {
        Instant recordedAt = ACCEPTED_AT.minus(MAXIMUM_LATENESS).plusNanos(999);
        Instant acceptedAt = ACCEPTED_AT.plusNanos(1);

        assertThatThrownBy(() -> policy.requireValidRecordedAt(existingSensor(),recordedAt,acceptedAt))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt exceeds maximum lateness");
    }



    @ParameterizedTest
    @CsvSource({"-1000,-1000","0,0","999,0"})
    void acceptsFutureSkewBoundaryAfterMicrosecondNormalization(long suppliedNanos,long expectedNanos) {
        Instant latest = ACCEPTED_AT.plus(MAXIMUM_FUTURE_SKEW);

        assertThat(policy.requireValidRecordedAt(existingSensor(),latest.plusNanos(suppliedNanos),ACCEPTED_AT))
                .isEqualTo(latest.plusNanos(expectedNanos));
    }



    @Test
    void rejectsTimestampOneMicrosecondBeyondFutureSkewBoundary() {
        Instant recordedAt = ACCEPTED_AT.plus(MAXIMUM_FUTURE_SKEW).plusNanos(1000);

        assertThatThrownBy(() -> policy.requireValidRecordedAt(existingSensor(),recordedAt,ACCEPTED_AT))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt exceeds maximum future skew");
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-15T12:00:00Z",
            "2026-01-15T12:59:59.999999999Z"
    })
    void acceptsRequiredSourceHourAtRetentionBoundaryUntilNextHour(String acceptanceTimestamp) {
        SensorReadingIngestionPolicy shortRetentionPolicy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,Duration.ofHours(12));
        Instant recordedAt = Instant.parse("2026-01-15T11:00:00Z");
        Instant acceptedAt = Instant.parse(acceptanceTimestamp);

        assertThat(shortRetentionPolicy.requireValidRecordedAt(existingSensor(),recordedAt,acceptedAt))
                .isEqualTo(recordedAt);
    }



    @Test
    void rejectsRecentReadingWhenItsLocalDayRequiresExpiredSourceData() {
        SensorReadingIngestionPolicy shortRetentionPolicy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,Duration.ofHours(12));
        Instant recordedAt = Instant.parse("2026-01-15T12:00:00Z");
        Instant acceptedAt = Instant.parse("2026-01-15T13:00:00Z");

        assertThatThrownBy(() -> shortRetentionPolicy.requireValidRecordedAt(existingSensor(),recordedAt,acceptedAt))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt requires source data outside raw retention");
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-16T12:00:00Z,2026-01-16T00:00:00Z",
            "Europe/Istanbul,2026-01-16T09:00:00Z,2026-01-15T21:00:00Z",
            "Asia/Kathmandu,2026-01-16T06:15:00Z,2026-01-15T18:00:00Z",
            "Europe/Berlin,2026-03-29T10:00:00Z,2026-03-28T23:00:00Z",
            "Europe/Berlin,2026-10-25T11:00:00Z,2026-10-24T22:00:00Z"
    })
    void requiresLocalDaySourceHoursAcrossTimezonesAndDst(String timezone,String recordedTimestamp,String requiredHourTimestamp) {
        Instant recordedAt = Instant.parse(recordedTimestamp);
        Instant requiredSourceHour = Instant.parse(requiredHourTimestamp);
        Duration rawRetention = Duration.between(requiredSourceHour,recordedAt);
        SensorReadingIngestionPolicy localDayPolicy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,rawRetention);
        Sensor sensor = sensorInZone(timezone,recordedAt.minus(Duration.ofDays(2)));

        assertThat(localDayPolicy.requireValidRecordedAt(sensor,recordedAt,recordedAt))
                .isEqualTo(recordedAt);

        Instant acceptedAfterSourceExpiry = recordedAt.plus(Duration.ofHours(1));

        assertThatThrownBy(() -> localDayPolicy.requireValidRecordedAt(sensor,recordedAt,acceptedAfterSourceExpiry))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt requires source data outside raw retention");
    }



    @Test
    void usesRecordedLocalDayWhenAcceptanceOccursOnFollowingDay() {
        SensorReadingIngestionPolicy shortRetentionPolicy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,Duration.ofHours(12));
        Instant recordedAt = Instant.parse("2026-01-15T23:59:59.999999999Z");
        Instant acceptedAt = Instant.parse("2026-01-16T00:00:00Z");

        assertThatThrownBy(() -> shortRetentionPolicy.requireValidRecordedAt(existingSensor(),recordedAt,acceptedAt))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt requires source data outside raw retention");
    }



    @Test
    void limitsRequiredSourceToCreationHourForSensorCreatedPartwayThroughDay() {
        SensorReadingIngestionPolicy shortRetentionPolicy = newPolicy(MAXIMUM_LATENESS,MAXIMUM_FUTURE_SKEW,Duration.ofHours(12));
        Sensor sensor = sensorInZone("UTC",Instant.parse("2026-01-15T10:45:00Z"));
        Instant recordedAt = Instant.parse("2026-01-15T21:00:00Z");
        Instant acceptedBeforeExpiry = Instant.parse("2026-01-15T22:59:59.999999999Z");
        Instant acceptedAtExpiry = Instant.parse("2026-01-15T23:00:00Z");

        assertThat(shortRetentionPolicy.requireValidRecordedAt(sensor,recordedAt,acceptedBeforeExpiry))
                .isEqualTo(recordedAt);

        assertThatThrownBy(() -> shortRetentionPolicy.requireValidRecordedAt(sensor,recordedAt,acceptedAtExpiry))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasMessage("recordedAt requires source data outside raw retention");
    }



    private static Sensor existingSensor() {
        return sensorInZone("UTC",ACCEPTED_AT.minus(Duration.ofDays(2)));
    }



    private static Sensor sensorInZone(String timezone,Instant createdAt) {
        AppUser owner = new AppUser("owner@example.com","test-password-hash",createdAt);
        return new Sensor(owner,SensorType.TEMPERATURE,"Living room","Istanbul","Kadikoy","Window",timezone,createdAt);
    }



    private static SensorReadingIngestionPolicy newPolicy(Duration maximumLateness,Duration maximumFutureSkew,Duration rawRetention) {
        SensorDataLifecyclePolicy lifecyclePolicy = new SensorDataLifecyclePolicy(
                rawRetention,Duration.ofDays(90),Duration.ofDays(730),Duration.ofMinutes(5),Duration.ofMinutes(5),
                Duration.ofMinutes(15),Duration.ofMinutes(15),Duration.ofHours(1),1000,500,10);

        return new SensorReadingIngestionPolicy(maximumLateness,maximumFutureSkew,lifecyclePolicy);
    }
}