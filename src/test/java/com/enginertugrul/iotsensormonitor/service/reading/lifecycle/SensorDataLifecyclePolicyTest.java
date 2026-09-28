package com.enginertugrul.iotsensormonitor.service.reading.lifecycle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.params.provider.Arguments.arguments;



class SensorDataLifecyclePolicyTest {



    @Test
    void preservesAllConfiguredValues() {
        SensorDataLifecyclePolicy policy = newPolicy(validDurations(),123,456,7);

        assertThat(policy.getRawRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(policy.getHourlyRetention()).isEqualTo(Duration.ofDays(90));
        assertThat(policy.getDailyRetention()).isEqualTo(Duration.ofDays(730));
        assertThat(policy.getHourlyRollupInterval()).isEqualTo(Duration.ofMinutes(2));
        assertThat(policy.getHourlyRollupGrace()).isEqualTo(Duration.ofMinutes(3));
        assertThat(policy.getDailyRollupInterval()).isEqualTo(Duration.ofMinutes(11));
        assertThat(policy.getDailyRollupGrace()).isEqualTo(Duration.ofMinutes(13));
        assertThat(policy.getPurgeInterval()).isEqualTo(Duration.ofMinutes(17));
        assertThat(policy.getDeleteBatchSize()).isEqualTo(123);
        assertThat(policy.getMaximumBucketsPerRun()).isEqualTo(456);
        assertThat(policy.getMaximumDeleteBatchesPerTierPerRun()).isEqualTo(7);
    }



    @ParameterizedTest(name = "{1} must not be null")
    @MethodSource("durationFields")
    void requiresEveryDuration(int index,String fieldName) {
        Duration[] durations = validDurations();
        durations[index] = null;

        assertThatNullPointerException()
                .isThrownBy(() -> newPolicy(durations,1000,500,10))
                .withMessage(fieldName + " must not be null");
    }



    @ParameterizedTest(name = "{1} must be strictly positive")
    @MethodSource("durationFields")
    void rejectsZeroAndNegativeDurations(int index,String fieldName) {
        for (Duration invalid : new Duration[]{Duration.ZERO,Duration.ofNanos(-1),Duration.ofSeconds(-1)}) {
            Duration[] durations = validDurations();
            durations[index] = invalid;

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> newPolicy(durations,1000,500,10))
                    .withMessage(fieldName + " must be positive");
        }
    }



    @Test
    void acceptsPositiveDurationsAndStrictRetentionOrderingAtNanosecondPrecision() {
        Duration minimum = Duration.ofNanos(1);
        Duration[] durations = {
                minimum,Duration.ofNanos(2),Duration.ofNanos(3),
                minimum,minimum,minimum,minimum,minimum
        };

        SensorDataLifecyclePolicy policy = newPolicy(durations,1,1,1);

        assertThat(policy.getRawRetention()).isEqualTo(minimum);
        assertThat(policy.getHourlyRetention()).isEqualTo(Duration.ofNanos(2));
        assertThat(policy.getDailyRetention()).isEqualTo(Duration.ofNanos(3));
        assertThat(policy.getHourlyRollupInterval()).isEqualTo(minimum);
        assertThat(policy.getHourlyRollupGrace()).isEqualTo(minimum);
        assertThat(policy.getDailyRollupInterval()).isEqualTo(minimum);
        assertThat(policy.getDailyRollupGrace()).isEqualTo(minimum);
        assertThat(policy.getPurgeInterval()).isEqualTo(minimum);
    }



    @ParameterizedTest
    @CsvSource({
            "90,90,730",
            "91,90,730",
            "30,90,90",
            "30,90,89"
    })
    void rejectsEqualOrReversedAdjacentRetentionPeriods(long rawDays,long hourlyDays,long dailyDays) {
        Duration[] durations = validDurations();
        durations[0] = Duration.ofDays(rawDays);
        durations[1] = Duration.ofDays(hourlyDays);
        durations[2] = Duration.ofDays(dailyDays);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> newPolicy(durations,1000,500,10))
                .withMessage("retention periods must satisfy rawRetention < hourlyRetention < dailyRetention");
    }



    @Test
    void acceptsGracePeriodsOneNanosecondBelowTheirUpperBounds() {
        Duration[] durations = validDurations();
        durations[4] = Duration.ofHours(1).minusNanos(1);
        durations[6] = Duration.ofDays(1).minusNanos(1);

        SensorDataLifecyclePolicy policy = newPolicy(durations,1000,500,10);

        assertThat(policy.getHourlyRollupGrace()).isEqualTo(Duration.ofHours(1).minusNanos(1));
        assertThat(policy.getDailyRollupGrace()).isEqualTo(Duration.ofDays(1).minusNanos(1));
    }



    @ParameterizedTest
    @CsvSource({
            "4,3600,hourlyRollupGrace must be shorter than one hour",
            "6,86400,dailyRollupGrace must be shorter than one day"
    })
    void rejectsGracePeriodsAtAndBeyondTheirUpperBounds(int index,long upperBoundSeconds,String message) {
        Duration upperBound = Duration.ofSeconds(upperBoundSeconds);

        for (Duration invalid : new Duration[]{upperBound,upperBound.plusNanos(1)}) {
            Duration[] durations = validDurations();
            durations[index] = invalid;

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> newPolicy(durations,1000,500,10))
                    .withMessage(message);
        }
    }



    @ParameterizedTest
    @CsvSource({"1,1,1","10000,10000,100"})
    void acceptsProcessingLimitsAtBothInclusiveBounds(int batchSize,int buckets,int deleteBatches) {
        SensorDataLifecyclePolicy policy = newPolicy(validDurations(),batchSize,buckets,deleteBatches);

        assertThat(policy.getDeleteBatchSize()).isEqualTo(batchSize);
        assertThat(policy.getMaximumBucketsPerRun()).isEqualTo(buckets);
        assertThat(policy.getMaximumDeleteBatchesPerTierPerRun()).isEqualTo(deleteBatches);
    }



    @ParameterizedTest
    @CsvSource({
            "-1,500,10,deleteBatchSize must be between 1 and 10000",
            "0,500,10,deleteBatchSize must be between 1 and 10000",
            "10001,500,10,deleteBatchSize must be between 1 and 10000",
            "1000,-1,10,maximumBucketsPerRun must be between 1 and 10000",
            "1000,0,10,maximumBucketsPerRun must be between 1 and 10000",
            "1000,10001,10,maximumBucketsPerRun must be between 1 and 10000",
            "1000,500,-1,maximumDeleteBatchesPerTierPerRun must be between 1 and 100",
            "1000,500,0,maximumDeleteBatchesPerTierPerRun must be between 1 and 100",
            "1000,500,101,maximumDeleteBatchesPerTierPerRun must be between 1 and 100"
    })
    void rejectsProcessingLimitsOutsideTheirInclusiveBounds(int batchSize,int buckets,int deleteBatches,String message) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> newPolicy(validDurations(),batchSize,buckets,deleteBatches))
                .withMessage(message);
    }



    private static Stream<Arguments> durationFields() {
        return Stream.of(
                arguments(0,"rawRetention"),
                arguments(1,"hourlyRetention"),
                arguments(2,"dailyRetention"),
                arguments(3,"hourlyRollupInterval"),
                arguments(4,"hourlyRollupGrace"),
                arguments(5,"dailyRollupInterval"),
                arguments(6,"dailyRollupGrace"),
                arguments(7,"purgeInterval")
        );
    }



    private static Duration[] validDurations() {
        return new Duration[]{
                Duration.ofDays(30),Duration.ofDays(90),Duration.ofDays(730),
                Duration.ofMinutes(2),Duration.ofMinutes(3),Duration.ofMinutes(11),
                Duration.ofMinutes(13),Duration.ofMinutes(17)
        };
    }



    private static SensorDataLifecyclePolicy newPolicy(Duration[] durations,int batchSize,int buckets,int deleteBatches) {
        return new SensorDataLifecyclePolicy(durations[0],durations[1],durations[2],durations[3],
                durations[4],durations[5],durations[6],durations[7],batchSize,buckets,deleteBatches);
    }
}