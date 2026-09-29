package com.enginertugrul.iotsensormonitor.service.reading.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;



class SensorReadingStreamPolicyTest {



    @ParameterizedTest
    @CsvSource({
            "PT1S,PT5S,PT30S,PT1S,1,1,1",
            "PT1M,PT5M,PT1H,PT2M,1000,32,2000",
            "PT5S,PT5S,PT30S,PT1S,100,4,100",
            "PT2.000000001S,PT15.000000001S,PT300.000000001S,PT15.000000001S,100,4,100"
    })
    void acceptsInclusiveBoundsAndPreservesConfiguredValues(
            String refresh,String heartbeat,String lifetime,String work,
            int maximumSubscriptions,int workerThreads,int workerQueueCapacity) {

        Duration[] durations = {
                Duration.parse(refresh),Duration.parse(heartbeat),
                Duration.parse(lifetime),Duration.parse(work)
        };
        int[] limits = {maximumSubscriptions,workerThreads,workerQueueCapacity};

        SensorReadingStreamPolicy policy = policy(durations,limits);

        assertThat(new Duration[] {
                policy.getRefreshInterval(),policy.getHeartbeatInterval(),
                policy.getConnectionLifetime(),policy.getWorkTimeout()
        }).containsExactly(durations);

        assertThat(new int[] {
                policy.getMaximumSubscriptions(),policy.getWorkerThreads(),policy.getWorkerQueueCapacity()
        }).containsExactly(limits);
    }



    @ParameterizedTest
    @MethodSource("durationBounds")
    void rejectsMissingDurationsAndValuesOutsideTheirBounds(
            int index,String fieldName,Duration minimum,Duration maximum) {

        Duration[] durations = defaultDurations();
        durations[index] = null;

        assertThatThrownBy(() -> policy(durations,defaultLimits()))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage(fieldName + " must not be null");

        List<Duration> invalidValues = List.of(
                Duration.ofSeconds(-1),Duration.ZERO,minimum.minusNanos(1),maximum.plusNanos(1));

        for (Duration invalidValue : invalidValues) {
            durations[index] = invalidValue;

            assertThatThrownBy(() -> policy(durations,defaultLimits()))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage(fieldName + " must be between " + minimum + " and " + maximum);
        }
    }



    @ParameterizedTest
    @CsvSource({
            "0,maximumSubscriptions,1000",
            "1,workerThreads,32",
            "2,workerQueueCapacity,2000"
    })
    void rejectsIntegerLimitsOutsideTheirBounds(int index,String fieldName,int maximum) {
        int[] limits = defaultLimits();

        for (int invalidValue : new int[] {-1,0,maximum + 1}) {
            limits[index] = invalidValue;

            assertThatThrownBy(() -> policy(defaultDurations(),limits))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage(fieldName + " must be between 1 and " + maximum);
        }
    }



    @Test
    void rejectsHeartbeatShorterThanRefreshInterval() {
        Duration[] durations = {
                Duration.ofSeconds(6),Duration.ofSeconds(5),
                Duration.ofSeconds(30),Duration.ofSeconds(1)
        };

        assertThatThrownBy(() -> policy(durations,defaultLimits()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("heartbeatInterval must not be shorter than refreshInterval");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsHeartbeatEqualToOrLongerThanConnectionLifetime(long additionalNanos) {
        Duration lifetime = Duration.ofSeconds(30);
        Duration[] durations = {
                Duration.ofSeconds(1),lifetime.plusNanos(additionalNanos),
                lifetime,Duration.ofSeconds(1)
        };

        assertThatThrownBy(() -> policy(durations,defaultLimits()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("heartbeatInterval must be shorter than connectionLifetime");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,1})
    void rejectsWorkTimeoutEqualToOrLongerThanConnectionLifetime(long additionalNanos) {
        Duration lifetime = Duration.ofSeconds(30);
        Duration[] durations = {
                Duration.ofSeconds(1),Duration.ofSeconds(5),
                lifetime,lifetime.plusNanos(additionalNanos)
        };

        assertThatThrownBy(() -> policy(durations,defaultLimits()))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("workTimeout must be shorter than connectionLifetime");
    }



    @Test
    void acceptsHeartbeatAndWorkTimeoutOneNanosecondShorterThanLifetime() {
        Duration lifetime = Duration.ofSeconds(30);
        Duration almostLifetime = lifetime.minusNanos(1);
        Duration[] durations = {
                Duration.ofSeconds(1),almostLifetime,lifetime,almostLifetime
        };

        SensorReadingStreamPolicy policy = policy(durations,defaultLimits());

        assertThat(policy.getHeartbeatInterval()).isEqualTo(almostLifetime);
        assertThat(policy.getWorkTimeout()).isEqualTo(almostLifetime);
    }



    private static Stream<Arguments> durationBounds() {
        return Stream.of(
                arguments(0,"refreshInterval",Duration.ofSeconds(1),Duration.ofMinutes(1)),
                arguments(1,"heartbeatInterval",Duration.ofSeconds(5),Duration.ofMinutes(5)),
                arguments(2,"connectionLifetime",Duration.ofSeconds(30),Duration.ofHours(1)),
                arguments(3,"workTimeout",Duration.ofSeconds(1),Duration.ofMinutes(2))
        );
    }



    private static Duration[] defaultDurations() {
        return new Duration[] {
                Duration.ofSeconds(2),Duration.ofSeconds(15),
                Duration.ofMinutes(5),Duration.ofSeconds(15)
        };
    }



    private static int[] defaultLimits() {
        return new int[] {100,4,100};
    }



    private static SensorReadingStreamPolicy policy(Duration[] durations,int[] limits) {
        return new SensorReadingStreamPolicy(
                durations[0],durations[1],durations[2],durations[3],limits[0],limits[1],limits[2]);
    }
}