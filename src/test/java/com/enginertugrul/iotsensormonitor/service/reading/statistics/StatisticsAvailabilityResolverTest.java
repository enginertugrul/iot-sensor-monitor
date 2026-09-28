package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;



@ExtendWith(MockitoExtension.class)
class StatisticsAvailabilityResolverTest {

    private static final Long SENSOR_ID = 41L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-01T08:40:00Z");
    private static final Instant AS_OF = Instant.parse("2026-04-15T12:34:56.123456789Z");

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    private StatisticsAvailabilityResolver resolver;



    @BeforeEach
    void setUp() {
        SensorDataLifecyclePolicy lifecyclePolicy = new SensorDataLifecyclePolicy(
                Duration.ofDays(2),Duration.ofDays(5),Duration.ofDays(10),
                Duration.ofMinutes(2),Duration.ofMinutes(7),Duration.ofMinutes(11),
                Duration.ofMinutes(17),Duration.ofHours(1),100,100,10);

        resolver = new StatisticsAvailabilityResolver(checkpointRepository,lifecyclePolicy);
    }



    @Test
    void rejectsMissingInputsBeforeReadingCheckpoints() {
        Sensor sensor = newSensor("UTC",CREATED_AT,null);

        assertThatNullPointerException()
                .isThrownBy(() -> resolver.resolve(null,AS_OF))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> resolver.resolve(sensor,null))
                .withMessage("asOf must not be null");

        verifyNoInteractions(checkpointRepository);
    }



    @Test
    void rejectsASnapshotBeforeSensorCreation() {
        Sensor sensor = newSensor("UTC",CREATED_AT,null);

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(sensor,CREATED_AT.minusNanos(1)))
                .withMessage("Statistics snapshot cannot precede sensor creation");

        verifyNoInteractions(checkpointRepository);
    }



    @Test
    void acceptsASnapshotExactlyAtCreationWithoutRepresentingAnEmptyRawRange() {
        Sensor sensor = newSensor("UTC",CREATED_AT,null);
        returnCheckpoints(null,null);

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,CREATED_AT);

        assertThat(snapshot.raw().retention().retentionWindow())
                .isEqualTo(new InstantRange(CREATED_AT,CREATED_AT));
        assertThat(snapshot.raw().representedCoverage()).isEmpty();
        assertThat(snapshot.history().hasReadings()).isFalse();
        assertThat(snapshot.hourly().requireRollupProgress().lag()).isEqualTo(Duration.ZERO);
        assertThat(snapshot.daily().requireRollupProgress().lag()).isEqualTo(Duration.ZERO);
    }



    @Test
    void resolvesTierSpecificRetentionWindowsWithoutInventingSummaryCoverage() {
        Sensor sensor = newSensor("Asia/Kathmandu",CREATED_AT,null);
        returnCheckpoints(null,null);

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,AS_OF);

        Instant rawCutoff = Instant.parse("2026-04-13T12:00:00Z");
        Instant hourlyCutoff = Instant.parse("2026-04-10T12:00:00Z");
        Instant dailyCutoff = Instant.parse("2026-04-05T12:34:56.123456789Z");
        Instant dailyWindowStart = Instant.parse("2026-04-04T18:15:00Z");

        assertThat(snapshot.history()).isEqualTo(new SensorHistory(CREATED_AT,Optional.empty()));

        assertThat(snapshot.raw().resolution()).isEqualTo(StatisticsResolution.RAW);
        assertThat(snapshot.raw().retention())
                .isEqualTo(new StatisticsTierRetention(rawCutoff,new InstantRange(rawCutoff,AS_OF)));
        assertThat(snapshot.raw().representedCoverage()).contains(new InstantRange(rawCutoff,AS_OF));
        assertThat(snapshot.raw().rollupProgress()).isEmpty();

        assertThat(snapshot.hourly().resolution()).isEqualTo(StatisticsResolution.HOURLY);
        assertThat(snapshot.hourly().retention())
                .isEqualTo(new StatisticsTierRetention(hourlyCutoff,new InstantRange(hourlyCutoff,AS_OF)));
        assertThat(snapshot.hourly().representedCoverage()).isEmpty();
        assertProgress(snapshot.hourly(),Optional.empty(),Instant.parse("2026-04-15T12:00:00Z"),Duration.ZERO);

        assertThat(snapshot.daily().resolution()).isEqualTo(StatisticsResolution.DAILY);
        assertThat(snapshot.daily().retention())
                .isEqualTo(new StatisticsTierRetention(dailyCutoff,new InstantRange(dailyWindowStart,AS_OF)));
        assertThat(snapshot.daily().representedCoverage()).isEmpty();
        assertProgress(snapshot.daily(),Optional.empty(),Instant.parse("2026-04-14T18:15:00Z"),Duration.ZERO);

        verify(checkpointRepository).findBySensorIdAndStage(SENSOR_ID,RollupStage.RAW_TO_HOURLY);
        verify(checkpointRepository).findBySensorIdAndStage(SENSOR_ID,RollupStage.HOURLY_TO_DAILY);
        verifyNoMoreInteractions(checkpointRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "2026-04-01T08:40:00Z,2026-04-13T12:00:00Z",
            "2026-04-13T12:00:00Z,2026-04-13T12:00:00Z",
            "2026-04-14T18:20:00Z,2026-04-14T18:20:00Z"
    })
    void rawRetentionStartsAtTheLaterOfCreationAndTheRoundedCutoff(String creationText,String expectedStartText) {
        Sensor sensor = newSensor("UTC",Instant.parse(creationText),null);
        Instant expectedStart = Instant.parse(expectedStartText);
        returnCheckpoints(null,null);

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,AS_OF);

        assertThat(snapshot.raw().retention().expirationCutoff())
                .isEqualTo(Instant.parse("2026-04-13T12:00:00Z"));
        assertThat(snapshot.raw().retention().retentionWindow())
                .isEqualTo(new InstantRange(expectedStart,AS_OF));
        assertThat(snapshot.raw().representedCoverage()).contains(new InstantRange(expectedStart,AS_OF));

        assertThat(snapshot.hourly().retention().retentionWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-04-10T12:00:00Z"));
        assertThat(snapshot.daily().retention().retentionWindow().startInclusive())
                .isEqualTo(Instant.parse("2026-04-05T00:00:00Z"));
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void missingAndUnadvancedCheckpointsMeasureLagFromFirstReadingOrigins(boolean initialized) {
        Instant firstReading = Instant.parse("2026-04-14T10:20:00Z");
        Instant hourlyOrigin = Instant.parse("2026-04-14T10:00:00Z");
        Instant dailyOrigin = Instant.parse("2026-04-13T18:15:00Z");
        Sensor sensor = newSensor("Asia/Kathmandu",CREATED_AT,firstReading);

        SensorRollupCheckpoint hourly = initialized
                ? checkpoint(sensor,RollupStage.RAW_TO_HOURLY,hourlyOrigin,0)
                : null;
        SensorRollupCheckpoint daily = initialized
                ? checkpoint(sensor,RollupStage.HOURLY_TO_DAILY,dailyOrigin,0)
                : null;
        returnCheckpoints(hourly,daily);

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,AS_OF);

        Optional<InstantRange> hourlyCoverage = initialized
                ? Optional.of(new InstantRange(hourlyOrigin,hourlyOrigin))
                : Optional.empty();
        Optional<InstantRange> dailyCoverage = initialized
                ? Optional.of(new InstantRange(dailyOrigin,dailyOrigin))
                : Optional.empty();

        assertThat(snapshot.history().firstReadingAt()).contains(firstReading);
        assertThat(snapshot.hourly().representedCoverage()).isEmpty();
        assertThat(snapshot.daily().representedCoverage()).isEmpty();
        assertProgress(snapshot.hourly(),hourlyCoverage,Instant.parse("2026-04-15T12:00:00Z"),Duration.ofHours(26));
        assertProgress(snapshot.daily(),dailyCoverage,Instant.parse("2026-04-14T18:15:00Z"),Duration.ofDays(1));
    }



    @Test
    void missingCheckpointsHaveNoLagWhenFirstReadingOriginsAreAtOrBeyondTheDueBoundaries() {
        Instant firstReading = Instant.parse("2026-04-15T00:10:00Z");
        Instant asOf = Instant.parse("2026-04-15T00:16:00Z");
        Sensor sensor = newSensor("UTC",CREATED_AT,firstReading);
        returnCheckpoints(null,null);

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,asOf);

        assertProgress(snapshot.hourly(),Optional.empty(),Instant.parse("2026-04-15T00:00:00Z"),Duration.ZERO);
        assertProgress(snapshot.daily(),Optional.empty(),Instant.parse("2026-04-14T00:00:00Z"),Duration.ZERO);
    }



    @ParameterizedTest
    @CsvSource({"11,false","12,false","13,true","24,true"})
    void hourlyRepresentationIntersectsVerifiedCoverageWithRetention(int completedHours,boolean represented) {
        Instant origin = Instant.parse("2026-04-10T00:00:00Z");
        Instant cutoff = Instant.parse("2026-04-10T12:00:00Z");
        Instant coveredUntil = origin.plusSeconds(completedHours * 3600L);
        Sensor sensor = newSensor("UTC",CREATED_AT,origin.plusSeconds(1800));
        returnCheckpoints(checkpoint(sensor,RollupStage.RAW_TO_HOURLY,origin,completedHours),null);

        StatisticsTierAvailability hourly = resolver.resolve(sensor,AS_OF).hourly();

        assertThat(hourly.requireRollupProgress().verifiedCoverage())
                .contains(new InstantRange(origin,coveredUntil));
        assertThat(hourly.verifies(new InstantRange(origin,origin.plusSeconds(3600)))).isTrue();

        if (represented) {
            assertThat(hourly.representedCoverage()).contains(new InstantRange(cutoff,coveredUntil));
        } else {
            assertThat(hourly.representedCoverage()).isEmpty();
        }
    }



    @ParameterizedTest
    @CsvSource({"0,false","1,false","2,true","3,true"})
    void dailyRepresentationUsesTheAlignedRetentionWindowRatherThanTheExactCutoff(int completedDays,boolean represented) {
        Instant origin = Instant.parse("2026-04-03T18:15:00Z");
        Instant retainedFrom = Instant.parse("2026-04-04T18:15:00Z");
        Instant coveredUntil = origin.plus(Duration.ofDays(completedDays));
        Sensor sensor = newSensor("Asia/Kathmandu",CREATED_AT,origin.plusSeconds(1800));
        returnCheckpoints(null,checkpoint(sensor,RollupStage.HOURLY_TO_DAILY,origin,completedDays));

        StatisticsTierAvailability daily = resolver.resolve(sensor,AS_OF).daily();

        assertThat(daily.retention().expirationCutoff())
                .isEqualTo(Instant.parse("2026-04-05T12:34:56.123456789Z"));
        assertThat(daily.retention().retentionWindow().startInclusive()).isEqualTo(retainedFrom);
        assertThat(daily.requireRollupProgress().verifiedCoverage())
                .contains(new InstantRange(origin,coveredUntil));

        if (represented) {
            assertThat(daily.representedCoverage()).contains(new InstantRange(retainedFrom,coveredUntil));
        } else {
            assertThat(daily.representedCoverage()).isEmpty();
        }
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void representedSummaryCoverageCannotExtendBeyondTheSnapshotTime(RollupStage stage) {
        Instant origin = Instant.parse("2026-04-14T00:00:00Z");
        Instant coveredUntil = Instant.parse("2026-04-16T00:00:00Z");
        Sensor sensor = newSensor("UTC",CREATED_AT,origin.plusSeconds(1800));
        int completedBuckets = stage == RollupStage.RAW_TO_HOURLY ? 48 : 2;
        returnCheckpoint(stage,checkpoint(sensor,stage,origin,completedBuckets));

        StatisticsAvailabilitySnapshot snapshot = resolver.resolve(sensor,AS_OF);
        StatisticsTierAvailability tier = stage == RollupStage.RAW_TO_HOURLY
                ? snapshot.hourly()
                : snapshot.daily();

        assertThat(tier.requireRollupProgress().verifiedCoverage())
                .contains(new InstantRange(origin,coveredUntil));
        assertThat(tier.representedCoverage()).contains(new InstantRange(origin,AS_OF));
        assertThat(tier.requireRollupProgress().lag()).isEqualTo(Duration.ZERO);
    }



    @ParameterizedTest
    @CsvSource({
            "-3600000000001,2026-04-15T11:00:00Z,0",
            "-1,2026-04-15T12:00:00Z,0",
            "0,2026-04-15T13:00:00Z,1",
            "1,2026-04-15T13:00:00Z,1"
    })
    void hourlyGraceControlsTheDueBoundaryAndLagUsesCoveredUntil(long offsetNanos,String dueText,long lagHours) {
        Instant origin = Instant.parse("2026-04-15T11:00:00Z");
        Instant coveredUntil = Instant.parse("2026-04-15T12:00:00Z");
        Instant asOf = Instant.parse("2026-04-15T13:07:00Z").plusNanos(offsetNanos);
        Sensor sensor = newSensor("UTC",CREATED_AT,origin.plusSeconds(1800));
        returnCheckpoints(checkpoint(sensor,RollupStage.RAW_TO_HOURLY,origin,1),null);

        StatisticsTierAvailability hourly = resolver.resolve(sensor,asOf).hourly();

        assertProgress(hourly,Optional.of(new InstantRange(origin,coveredUntil)),
                Instant.parse(dueText),Duration.ofHours(lagHours));
    }



    @ParameterizedTest
    @CsvSource({
            "Europe/Berlin,2026-03-29T10:00:00Z,2026-03-29T22:17:00Z,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z,23",
            "Europe/Berlin,2026-10-25T12:00:00Z,2026-10-25T23:17:00Z,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z,25",
            "Asia/Kathmandu,2026-01-15T18:30:00Z,2026-01-16T18:32:00Z,2026-01-15T18:15:00Z,2026-01-16T18:15:00Z,24"
    })
    void dailyGraceUsesLocalMidnightAndLagMeasuresActualElapsedTime(String zoneId,String firstText,String thresholdText,String originText,String dueText,long lagHours) {
        Instant firstReading = Instant.parse(firstText);
        Instant threshold = Instant.parse(thresholdText);
        Instant origin = Instant.parse(originText);
        Instant due = Instant.parse(dueText);
        Sensor sensor = newSensor(zoneId,firstReading.minusSeconds(7200),firstReading);
        returnCheckpoints(null,null);

        for (long offsetNanos : new long[]{-1,0,1}) {
            StatisticsTierAvailability daily = resolver.resolve(sensor,threshold.plusNanos(offsetNanos)).daily();
            Instant expectedDue = offsetNanos < 0 ? origin : due;
            Duration expectedLag = offsetNanos < 0 ? Duration.ZERO : Duration.ofHours(lagHours);

            assertProgress(daily,Optional.empty(),expectedDue,expectedLag);
            assertThat(daily.representedCoverage()).isEmpty();
        }
    }



    @ParameterizedTest
    @CsvSource({
            "2026-03-30T12:00:00Z,2026-03-20T12:00:00Z,2026-03-19T23:00:00Z",
            "2026-10-26T12:00:00Z,2026-10-16T12:00:00Z,2026-10-15T22:00:00Z"
    })
    void dailyRetentionSubtractsElapsedDurationBeforeAligningToLocalMidnight(String asOfText,String cutoffText,String windowStartText) {
        Instant asOf = Instant.parse(asOfText);
        Sensor sensor = newSensor("Europe/Berlin",asOf.minus(Duration.ofDays(20)),null);
        returnCheckpoints(null,null);

        StatisticsTierRetention retention = resolver.resolve(sensor,asOf).daily().retention();

        assertThat(retention.expirationCutoff()).isEqualTo(Instant.parse(cutoffText));
        assertThat(retention.retentionWindow())
                .isEqualTo(new InstantRange(Instant.parse(windowStartText),asOf));
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void rejectsACheckpointReturnedForTheWrongStage(RollupStage requestedStage) {
        Instant origin = Instant.parse("2026-04-14T00:00:00Z");
        Sensor sensor = newSensor("UTC",CREATED_AT,origin.plusSeconds(1800));
        RollupStage wrongStage = requestedStage == RollupStage.RAW_TO_HOURLY
                ? RollupStage.HOURLY_TO_DAILY
                : RollupStage.RAW_TO_HOURLY;
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,wrongStage,origin,AS_OF);
        returnCheckpoint(requestedStage,checkpoint);

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(sensor,AS_OF))
                .withMessage("Unexpected rollup checkpoint stage");
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void rejectsACheckpointForASensorWithoutAFirstReading(RollupStage stage) {
        Sensor sensor = newSensor("UTC",CREATED_AT,null);
        Instant origin = Instant.parse("2026-04-14T00:00:00Z");
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,origin,AS_OF);
        returnCheckpoint(stage,checkpoint);

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(sensor,AS_OF))
                .withMessage("A rollup checkpoint exists for a sensor with no first reading");
    }



    @ParameterizedTest
    @CsvSource({
            "RAW_TO_HOURLY,-3600",
            "RAW_TO_HOURLY,3600",
            "HOURLY_TO_DAILY,-86400",
            "HOURLY_TO_DAILY,86400"
    })
    void rejectsMismatchedCoverageOriginsEvenWhenTheCheckpointCoverageHasExpired(RollupStage stage,long offsetSeconds) {
        Instant expectedOrigin = Instant.parse("2026-04-02T00:00:00Z");
        Sensor sensor = newSensor("UTC",CREATED_AT,expectedOrigin.plusSeconds(1800));
        Instant wrongOrigin = expectedOrigin.plusSeconds(offsetSeconds);
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,wrongOrigin,AS_OF);
        returnCheckpoint(stage,checkpoint);

        assertThatIllegalStateException()
                .isThrownBy(() -> resolver.resolve(sensor,AS_OF))
                .withMessage("Checkpoint coverage origin does not match the sensor's first reading");
    }



    private void returnCheckpoints(SensorRollupCheckpoint hourly,SensorRollupCheckpoint daily) {
        when(checkpointRepository.findBySensorIdAndStage(SENSOR_ID,RollupStage.RAW_TO_HOURLY))
                .thenReturn(Optional.ofNullable(hourly));
        when(checkpointRepository.findBySensorIdAndStage(SENSOR_ID,RollupStage.HOURLY_TO_DAILY))
                .thenReturn(Optional.ofNullable(daily));
    }



    private void returnCheckpoint(RollupStage stage,SensorRollupCheckpoint checkpoint) {
        if (stage == RollupStage.RAW_TO_HOURLY) {
            returnCheckpoints(checkpoint,null);
        } else {
            returnCheckpoints(null,checkpoint);
        }
    }



    private static Sensor newSensor(String zoneId,Instant createdAt,Instant firstReadingAt) {
        Sensor sensor = new Sensor(user(),SensorType.TEMPERATURE,"Living room","Istanbul","Kadikoy","Window",zoneId,createdAt);
        setField(sensor,"id",SENSOR_ID);

        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,firstReadingAt);
        }

        return sensor;
    }



    private static SensorRollupCheckpoint checkpoint(Sensor sensor,RollupStage stage,Instant origin,int completedBuckets) {
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,origin,origin);
        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        Instant bucketStart = origin;

        for (int index = 0; index < completedBuckets; index++) {
            Instant bucketEnd = stage == RollupStage.RAW_TO_HOURLY
                    ? bucketStart.plusSeconds(3600)
                    : bucketStart.atZone(timeZone).plusDays(1).toInstant();

            checkpoint.recordAttempt(bucketStart,bucketEnd);
            checkpoint.advanceContiguously(bucketStart,bucketEnd,bucketEnd);
            bucketStart = bucketEnd;
        }

        return checkpoint;
    }



    private static void assertProgress(StatisticsTierAvailability tier,Optional<InstantRange> coverage,Instant due,Duration lag) {
        StatisticsRollupProgress progress = tier.requireRollupProgress();

        assertThat(progress.verifiedCoverage()).isEqualTo(coverage);
        assertThat(progress.rollupDueUntilExclusive()).isEqualTo(due);
        assertThat(progress.lag()).isEqualTo(lag);
        assertThat(progress.isDelayed()).isEqualTo(!lag.isZero());
    }
}