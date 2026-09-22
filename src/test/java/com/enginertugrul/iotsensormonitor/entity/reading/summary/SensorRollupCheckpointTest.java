package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SensorRollupCheckpointTest {

    private static final Instant START = CREATED_AT;
    private static final Instant FIRST_END = START.plusSeconds(3600);
    private static final Instant SECOND_END = START.plusSeconds(7200);



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void initializesEmptyCoverageWithoutAttemptOrSuccessHistory(RollupStage stage) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,START,START);

        assertThat(checkpoint.getId()).isNull();
        assertThat(checkpoint.getSensor()).isSameAs(sensor);
        assertThat(checkpoint.getStage()).isEqualTo(stage);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(checkpoint);
    }



    @Test
    void rejectsMissingInitializationArguments() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatNullPointerException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(null,RollupStage.RAW_TO_HOURLY,START,START))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(sensor,null,START,START))
                .withMessage("stage must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(sensor,RollupStage.RAW_TO_HOURLY,null,START))
                .withMessage("coverageStartedAt must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(sensor,RollupStage.RAW_TO_HOURLY,START,null))
                .withMessage("initializedAt must not be null");
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void rejectsInitializationBeforeCoverageStart(RollupStage stage) {
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(sensor,stage,START,START.minusNanos(1)))
                .withMessage("createdAt must not be before coverageStartedAt");
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-15T12:00:00.000000001Z",
            "2026-01-15T12:00:01Z",
            "2026-01-15T12:15:00Z"
    })
    void rejectsNonHourlyCoverageStartForRawToHourlyStage(String value) {
        Instant coverageStart = Instant.parse(value);
        Sensor sensor = sensor(SensorType.TEMPERATURE);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SensorRollupCheckpoint.initialize(sensor,RollupStage.RAW_TO_HOURLY,coverageStart,coverageStart))
                .withMessage("coverageStartedAt must be aligned to a UTC hour");
    }



    @Test
    void recordsAndRetriesNextBucketWithoutAdvancingCoverage() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        checkpoint.recordAttempt(START,START);

        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(START);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);

        Instant retriedAt = START.plusSeconds(60);
        checkpoint.recordAttempt(START,retriedAt);
        checkpoint.recordAttempt(START,retriedAt);

        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(retriedAt);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(START);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(retriedAt);
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
    }



    @ParameterizedTest
    @ValueSource(longs = {-3600,3600})
    void rejectsAttemptsOutsideNextContiguousBucket(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.recordAttempt(START.plusSeconds(offsetSeconds),SECOND_END))
                .withMessage("Only the next contiguous bucket may be attempted");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(checkpoint);
    }



    @Test
    void rejectsAttemptBeforeBucketStart() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.recordAttempt(START,START.minusNanos(1)))
                .withMessage("attemptedAt must not be before bucketStart");

        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(checkpoint);
    }



    @Test
    void rejectsAttemptBeforeCheckpointCreation() {
        Instant initializedAt = START.plusSeconds(60);
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor(SensorType.TEMPERATURE),RollupStage.RAW_TO_HOURLY,START,initializedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.recordAttempt(START,initializedAt.minusNanos(1)))
                .withMessage("Checkpoint operational timestamps must not move backwards");

        assertThat(checkpoint.getUpdatedAt()).isEqualTo(initializedAt);
        assertHistoryCleared(checkpoint);
    }



    @Test
    void rejectsAttemptBeforeLatestOperationalTimestamp() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        Instant attemptedAt = START.plusSeconds(60);
        checkpoint.recordAttempt(START,attemptedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.recordAttempt(START,attemptedAt.minusNanos(1)))
                .withMessage("Checkpoint operational timestamps must not move backwards");

        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(START);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(attemptedAt);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(attemptedAt);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
    }



    @Test
    void rejectsMissingAttemptArguments() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.recordAttempt(null,FIRST_END))
                .withMessage("bucketStart must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.recordAttempt(START,null))
                .withMessage("attemptedAt must not be null");

        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(checkpoint);
    }



    @Test
    void advancesConsecutiveHoursAndPreservesPreviousSuccessWhileNextBucketIsAttempted() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        checkpoint.recordAttempt(START,FIRST_END);
        checkpoint.advanceContiguously(START,FIRST_END,FIRST_END);

        assertSuccessfulBucket(checkpoint,START,FIRST_END,FIRST_END);

        checkpoint.recordAttempt(FIRST_END,FIRST_END);

        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(FIRST_END);
        assertSuccessfulBucket(checkpoint,START,FIRST_END,FIRST_END);

        checkpoint.advanceContiguously(FIRST_END,SECOND_END,SECOND_END);

        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
    }



    @Test
    void rejectsSuccessWithoutRecordedAttempt() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();

        assertThatIllegalStateException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,FIRST_END,FIRST_END))
                .withMessage("The bucket must be recorded as attempted before it succeeds");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(START);
        assertHistoryCleared(checkpoint);
    }



    @ParameterizedTest
    @ValueSource(longs = {-3600,3600})
    void rejectsAdvancementThatDoesNotStartAtCoveredUntil(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,FIRST_END);
        Instant bucketStart = START.plusSeconds(offsetSeconds);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(bucketStart,bucketStart.plusSeconds(3600),SECOND_END))
                .withMessage("Checkpoint coverage may only advance from coveredUntil");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
    }



    @Test
    void rejectsAdvancingAnAlreadyCompletedBucketAgain() {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(FIRST_END,SECOND_END,SECOND_END.plusSeconds(60)))
                .withMessage("Checkpoint coverage may only advance from coveredUntil");

        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,-3600})
    void rejectsEmptyOrReversedBuckets(long durationSeconds) {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,FIRST_END);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,START.plusSeconds(durationSeconds),FIRST_END))
                .withMessage("bucketStart must be before bucketEnd");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
    }



    @ParameterizedTest
    @CsvSource({
            "1800,bucketEnd must be aligned to a UTC hour",
            "3601,bucketEnd must be aligned to a UTC hour",
            "7200,Raw-to-hourly coverage must advance one UTC hour at a time"
    })
    void rejectsMisalignedOrMultiHourBucketEnds(long durationSeconds,String message) {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,SECOND_END);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,START.plusSeconds(durationSeconds),SECOND_END))
                .withMessage(message);

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(SECOND_END);
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
    }



    @Test
    void rejectsSuccessBeforeBucketEndOrLatestAttempt() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,FIRST_END.minusNanos(1));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,FIRST_END,FIRST_END.minusNanos(1)))
                .withMessage("successfulAt must not be before the attempt or bucket end");

        Instant retriedAt = FIRST_END.plusSeconds(60);
        checkpoint.recordAttempt(START,retriedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,FIRST_END,retriedAt.minusNanos(1)))
                .withMessage("successfulAt must not be before the attempt or bucket end");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(retriedAt);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(retriedAt);
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
    }



    @Test
    void rejectsMissingAdvancementArguments() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,FIRST_END);

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.advanceContiguously(null,FIRST_END,FIRST_END))
                .withMessage("bucketStart must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,null,FIRST_END))
                .withMessage("bucketEnd must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.advanceContiguously(START,FIRST_END,null))
                .withMessage("successfulAt must not be null");

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
    }



    @ParameterizedTest
    @CsvSource({
            "Europe/Berlin,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z",
            "Europe/Berlin,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z",
            "Asia/Kathmandu,2026-01-15T18:15:00Z,2026-01-16T18:15:00Z"
    })
    void dailyStageAcceptsDstDaysAndFractionalHourBoundaries(String zoneId,String start,String end) {
        Instant bucketStart = Instant.parse(start);
        Instant bucketEnd = Instant.parse(end);
        Sensor sensor = new Sensor(user(),SensorType.TEMPERATURE,"Living room","Istanbul","Kadikoy","Window",zoneId,CREATED_AT);
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,RollupStage.HOURLY_TO_DAILY,bucketStart,bucketStart);

        checkpoint.recordAttempt(bucketStart,bucketEnd);
        checkpoint.advanceContiguously(bucketStart,bucketEnd,bucketEnd);

        assertSuccessfulBucket(checkpoint,bucketStart,bucketEnd,bucketEnd);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(bucketStart);
        assertThat(checkpoint.requiresInvalidationFrom(bucketStart)).isTrue();

        checkpoint.invalidateFrom(bucketStart,bucketEnd.plusSeconds(60));

        assertThat(checkpoint.getCoveredUntil()).isEqualTo(bucketStart);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(bucketStart);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(bucketEnd.plusSeconds(60));
        assertHistoryCleared(checkpoint);
    }



    @ParameterizedTest
    @CsvSource({
            "-3600,true",
            "0,true",
            "3600,true",
            "7200,false",
            "10800,false"
    })
    void requiresInvalidationOnlyBeforeCoveredUntil(long offsetSeconds,boolean expected) {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();

        assertThat(checkpoint.requiresInvalidationFrom(START.plusSeconds(offsetSeconds))).isEqualTo(expected);

        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,3600})
    void invalidationRewindsCoverageClearsHistoryAndRequiresANewAttempt(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();
        Instant bucketStart = START.plusSeconds(offsetSeconds);
        Instant bucketEnd = bucketStart.plusSeconds(3600);
        Instant invalidatedAt = SECOND_END.plusSeconds(60);

        checkpoint.invalidateFrom(bucketStart,invalidatedAt);

        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(bucketStart);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(invalidatedAt);
        assertHistoryCleared(checkpoint);

        assertThatIllegalStateException()
                .isThrownBy(() -> checkpoint.advanceContiguously(bucketStart,bucketEnd,invalidatedAt))
                .withMessage("The bucket must be recorded as attempted before it succeeds");

        checkpoint.recordAttempt(bucketStart,invalidatedAt);
        checkpoint.advanceContiguously(bucketStart,bucketEnd,invalidatedAt);

        assertSuccessfulBucket(checkpoint,bucketStart,bucketEnd,invalidatedAt);
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
    }



    @Test
    void invalidationBeforeCoverageOriginMovesOriginAndCoveredUntilEarlier() {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();
        Instant earlierStart = START.minusSeconds(3600);
        Instant invalidatedAt = SECOND_END.plusSeconds(60);

        checkpoint.invalidateFrom(earlierStart,invalidatedAt);

        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(earlierStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(earlierStart);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(invalidatedAt);
        assertHistoryCleared(checkpoint);
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,0,7199,7200})
    void invalidationWithAnOlderOrEqualTimestampDoesNotMoveUpdatedAtBackwards(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();

        checkpoint.invalidateFrom(FIRST_END,START.plusSeconds(offsetSeconds));

        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(SECOND_END);
        assertHistoryCleared(checkpoint);
    }



    @ParameterizedTest
    @ValueSource(longs = {7200,10800})
    void invalidationAtOrAfterCoveredUntilLeavesCoverageHistoryAndTimestampsUnchanged(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();

        checkpoint.invalidateFrom(START.plusSeconds(offsetSeconds),SECOND_END.plusSeconds(60));

        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(START);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(START);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(FIRST_END);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(SECOND_END);
        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
    }



    @ParameterizedTest
    @ValueSource(longs = {0,7200})
    void rejectsMisalignedHourlyInvalidationEvenWhenItWouldOtherwiseBeANoOp(long offsetSeconds) {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();
        Instant bucketStart = START.plusSeconds(offsetSeconds).plusNanos(1);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.requiresInvalidationFrom(bucketStart))
                .withMessage("bucketStart must be aligned to a UTC hour");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> checkpoint.invalidateFrom(bucketStart,SECOND_END.plusSeconds(60)))
                .withMessage("bucketStart must be aligned to a UTC hour");

        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
    }



    @Test
    void rejectsMissingInvalidationArguments() {
        SensorRollupCheckpoint checkpoint = coveredHourlyCheckpoint();

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.requiresInvalidationFrom(null))
                .withMessage("bucketStart must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.invalidateFrom(null,SECOND_END))
                .withMessage("bucketStart must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> checkpoint.invalidateFrom(FIRST_END,null))
                .withMessage("invalidatedAt must not be null");

        assertSuccessfulBucket(checkpoint,FIRST_END,SECOND_END,SECOND_END);
    }



    private static SensorRollupCheckpoint hourlyCheckpoint() {
        return SensorRollupCheckpoint.initialize(sensor(SensorType.TEMPERATURE),RollupStage.RAW_TO_HOURLY,START,START);
    }



    private static SensorRollupCheckpoint coveredHourlyCheckpoint() {
        SensorRollupCheckpoint checkpoint = hourlyCheckpoint();
        checkpoint.recordAttempt(START,FIRST_END);
        checkpoint.advanceContiguously(START,FIRST_END,FIRST_END);
        checkpoint.recordAttempt(FIRST_END,SECOND_END);
        checkpoint.advanceContiguously(FIRST_END,SECOND_END,SECOND_END);
        return checkpoint;
    }



    private static void assertSuccessfulBucket(SensorRollupCheckpoint checkpoint,Instant start,Instant end,Instant successfulAt) {
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(end);
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isEqualTo(start);
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isEqualTo(end);
        assertThat(checkpoint.getLastSuccessfulAt()).isEqualTo(successfulAt);
        assertThat(checkpoint.getLastAdvancedAt()).isEqualTo(successfulAt);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(successfulAt);
    }



    private static void assertHistoryCleared(SensorRollupCheckpoint checkpoint) {
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
    }
}