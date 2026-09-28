package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailySensorRollupService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class DailySensorRollupSchedulerTest {

    private static final DailyRollupRunResult NO_WORK =
            new DailyRollupRunResult(DailyRollupRunResult.Status.NO_WORK,0,0,0,0,0,0,0,0,false,Duration.ZERO,null);

    @Mock
    private DailySensorRollupService service;



    @ParameterizedTest
    @CsvSource({
            "PT15M,2026-01-15T00:14:59.999999999Z,2026-01-14T23:59:59.999999999Z",
            "PT15M,2026-01-15T00:15:00Z,2026-01-15T00:00:00Z",
            "PT15M,2026-01-15T00:15:00.000000001Z,2026-01-15T00:00:00.000000001Z",
            "PT15M,2026-01-15T12:34:56.123456789Z,2026-01-15T12:19:56.123456789Z",
            "PT19M45S,2026-01-15T12:34:56.123456789Z,2026-01-15T12:15:11.123456789Z",
            "PT19M45S,2026-01-15T00:10:00Z,2026-01-14T23:50:15Z"
    })
    void subtractsConfiguredGraceWithoutTruncatingTheCutoff(String grace,String currentTime,String expectedCutoff) {
        Instant cutoff = Instant.parse(expectedCutoff);
        Clock clock = Clock.fixed(Instant.parse(currentTime),ZoneOffset.ofHoursMinutes(5,45));
        DailySensorRollupScheduler scheduler =
                new DailySensorRollupScheduler(service,policy(Duration.parse(grace)),clock);
        when(service.rollUpClosedLocalDays(cutoff)).thenReturn(NO_WORK);

        assertThatCode(scheduler::rollUpClosedLocalDays).doesNotThrowAnyException();

        verify(service).rollUpClosedLocalDays(cutoff);
        verifyNoMoreInteractions(service);
    }



    @Test
    void usesTheStartInstantWhenTheClockAdvancesDuringExecution() {
        Instant startedAt = Instant.parse("2026-01-15T12:34:56.123456789Z");
        Instant cutoff = Instant.parse("2026-01-15T12:19:56.123456789Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(startedAt,startedAt.plusSeconds(86400));
        when(service.rollUpClosedLocalDays(cutoff)).thenReturn(NO_WORK);
        DailySensorRollupScheduler scheduler =
                new DailySensorRollupScheduler(service,policy(Duration.ofMinutes(15)),clock);

        assertThatCode(scheduler::rollUpClosedLocalDays).doesNotThrowAnyException();

        verify(service).rollUpClosedLocalDays(cutoff);
        verifyNoMoreInteractions(service);
    }



    @Test
    void containsServiceFailureAndUsesAFreshCutoffOnTheNextInvocation() {
        Instant firstStartedAt = Instant.parse("2026-01-15T00:15:00Z");
        Instant nextStartedAt = Instant.parse("2026-01-16T00:15:00Z");
        Instant firstCutoff = Instant.parse("2026-01-15T00:00:00Z");
        Instant nextCutoff = Instant.parse("2026-01-16T00:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(firstStartedAt);
        when(service.rollUpClosedLocalDays(firstCutoff)).thenThrow(new IllegalStateException("Daily rollup failed"));
        DailySensorRollupScheduler scheduler =
                new DailySensorRollupScheduler(service,policy(Duration.ofMinutes(15)),clock);

        assertThatCode(scheduler::rollUpClosedLocalDays).doesNotThrowAnyException();

        verify(service).rollUpClosedLocalDays(firstCutoff);
        verifyNoMoreInteractions(service);

        when(clock.instant()).thenReturn(nextStartedAt);
        when(service.rollUpClosedLocalDays(nextCutoff)).thenReturn(NO_WORK);

        assertThatCode(scheduler::rollUpClosedLocalDays).doesNotThrowAnyException();

        verify(service).rollUpClosedLocalDays(nextCutoff);
        verifyNoMoreInteractions(service);
    }



    private static SensorDataLifecyclePolicy policy(Duration dailyGrace) {
        return new SensorDataLifecyclePolicy(
                Duration.ofDays(30),Duration.ofDays(90),Duration.ofDays(730),
                Duration.ofMinutes(5),Duration.ofMinutes(5),
                Duration.ofMinutes(15),dailyGrace,
                Duration.ofHours(1),1000,500,10);
    }
}