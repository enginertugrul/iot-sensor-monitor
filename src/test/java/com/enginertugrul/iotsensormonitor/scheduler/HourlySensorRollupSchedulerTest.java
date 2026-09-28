package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlySensorRollupService;
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
class HourlySensorRollupSchedulerTest {

    private static final HourlyRollupRunResult NO_WORK =
            new HourlyRollupRunResult(HourlyRollupRunResult.Status.NO_WORK,0,0,0,0,0,false,null);

    @Mock
    private HourlySensorRollupService service;



    @ParameterizedTest
    @CsvSource({
            "PT5M,2026-01-15T12:04:59.999999999Z,2026-01-15T11:00:00Z",
            "PT5M,2026-01-15T12:05:00Z,2026-01-15T12:00:00Z",
            "PT5M,2026-01-15T12:05:00.000000001Z,2026-01-15T12:00:00Z",
            "PT5M,2026-01-15T00:04:59.999999999Z,2026-01-14T23:00:00Z",
            "PT7M30S,2026-01-15T12:07:29.999999999Z,2026-01-15T11:00:00Z",
            "PT7M30S,2026-01-15T12:07:30Z,2026-01-15T12:00:00Z"
    })
    void subtractsConfiguredGraceBeforeTruncatingToUtcHour(String grace,String currentTime,String expectedCutoff) {
        Instant cutoff = Instant.parse(expectedCutoff);
        Clock clock = Clock.fixed(Instant.parse(currentTime),ZoneOffset.ofHoursMinutes(5,45));
        HourlySensorRollupScheduler scheduler =
                new HourlySensorRollupScheduler(service,policy(Duration.parse(grace)),clock);
        when(service.rollUpClosedHours(cutoff)).thenReturn(NO_WORK);

        assertThatCode(scheduler::rollUpClosedUtcHours).doesNotThrowAnyException();

        verify(service).rollUpClosedHours(cutoff);
        verifyNoMoreInteractions(service);
    }



    @Test
    void usesTheStartInstantWhenTheClockAdvancesDuringExecution() {
        Instant startedAt = Instant.parse("2026-01-15T12:05:00Z");
        Instant cutoff = Instant.parse("2026-01-15T12:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(startedAt,startedAt.plusSeconds(3600));
        when(service.rollUpClosedHours(cutoff)).thenReturn(NO_WORK);
        HourlySensorRollupScheduler scheduler =
                new HourlySensorRollupScheduler(service,policy(Duration.ofMinutes(5)),clock);

        assertThatCode(scheduler::rollUpClosedUtcHours).doesNotThrowAnyException();

        verify(service).rollUpClosedHours(cutoff);
        verifyNoMoreInteractions(service);
    }



    @Test
    void containsServiceFailureAndUsesAFreshCutoffOnTheNextInvocation() {
        Instant firstStartedAt = Instant.parse("2026-01-15T12:05:00Z");
        Instant nextStartedAt = Instant.parse("2026-01-15T13:05:00Z");
        Instant firstCutoff = Instant.parse("2026-01-15T12:00:00Z");
        Instant nextCutoff = Instant.parse("2026-01-15T13:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(firstStartedAt);
        when(service.rollUpClosedHours(firstCutoff)).thenThrow(new IllegalStateException("Hourly rollup failed"));
        HourlySensorRollupScheduler scheduler =
                new HourlySensorRollupScheduler(service,policy(Duration.ofMinutes(5)),clock);

        assertThatCode(scheduler::rollUpClosedUtcHours).doesNotThrowAnyException();

        verify(service).rollUpClosedHours(firstCutoff);
        verifyNoMoreInteractions(service);

        when(clock.instant()).thenReturn(nextStartedAt);
        when(service.rollUpClosedHours(nextCutoff)).thenReturn(NO_WORK);

        assertThatCode(scheduler::rollUpClosedUtcHours).doesNotThrowAnyException();

        verify(service).rollUpClosedHours(nextCutoff);
        verifyNoMoreInteractions(service);
    }



    private static SensorDataLifecyclePolicy policy(Duration hourlyGrace) {
        return new SensorDataLifecyclePolicy(
                Duration.ofDays(30),Duration.ofDays(90),Duration.ofDays(730),
                Duration.ofMinutes(5),hourlyGrace,
                Duration.ofMinutes(15),Duration.ofMinutes(15),
                Duration.ofHours(1),1000,500,10);
    }
}