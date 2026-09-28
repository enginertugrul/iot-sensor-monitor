package com.enginertugrul.iotsensormonitor.scheduler;

import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeTierResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeTierResult.Tier;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataRetentionRunResult;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataRetentionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorDataRetentionSchedulerTest {

    private static final SensorDataLifecyclePolicy POLICY = new SensorDataLifecyclePolicy(
            Duration.ofDays(2),Duration.ofDays(4),Duration.ofDays(8),
            Duration.ofMinutes(5),Duration.ofMinutes(5),
            Duration.ofMinutes(15),Duration.ofMinutes(15),
            Duration.ofHours(1),2,10,3);

    @Mock
    private SensorDataRetentionService service;



    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-15T00:00:00Z",
            "2026-01-15T12:34:56.123456789Z",
            "2026-01-15T23:59:59.999999999Z"
    })
    void passesTheExactClockInstantWithoutApplyingRetentionOrGrace(String currentTime) {
        Instant now = Instant.parse(currentTime);
        Clock clock = Clock.fixed(now,ZoneOffset.ofHoursMinutes(5,45));
        SensorDataRetentionScheduler scheduler = new SensorDataRetentionScheduler(service,POLICY,clock);
        when(service.purgeExpiredData(now)).thenReturn(noWorkResult(now));

        assertThatCode(scheduler::purgeExpiredSensorData).doesNotThrowAnyException();

        verify(service).purgeExpiredData(now);
        verifyNoMoreInteractions(service);
    }



    @Test
    void usesTheStartInstantWhenTheClockAdvancesDuringExecution() {
        Instant startedAt = Instant.parse("2026-01-15T12:34:56.123456789Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(startedAt,startedAt.plusSeconds(3600));
        when(service.purgeExpiredData(startedAt)).thenReturn(noWorkResult(startedAt));
        SensorDataRetentionScheduler scheduler = new SensorDataRetentionScheduler(service,POLICY,clock);

        assertThatCode(scheduler::purgeExpiredSensorData).doesNotThrowAnyException();

        verify(service).purgeExpiredData(startedAt);
        verifyNoMoreInteractions(service);
    }



    @Test
    void containsServiceFailureAndUsesAFreshTimeOnTheNextInvocation() {
        Instant firstStartedAt = Instant.parse("2026-01-15T12:34:56.123456789Z");
        Instant nextStartedAt = Instant.parse("2026-01-15T13:34:56.123456789Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(firstStartedAt);
        when(service.purgeExpiredData(firstStartedAt)).thenThrow(new IllegalStateException("Retention failed"));
        SensorDataRetentionScheduler scheduler = new SensorDataRetentionScheduler(service,POLICY,clock);

        assertThatCode(scheduler::purgeExpiredSensorData).doesNotThrowAnyException();

        verify(service).purgeExpiredData(firstStartedAt);
        verifyNoMoreInteractions(service);

        when(clock.instant()).thenReturn(nextStartedAt);
        when(service.purgeExpiredData(nextStartedAt)).thenReturn(noWorkResult(nextStartedAt));

        assertThatCode(scheduler::purgeExpiredSensorData).doesNotThrowAnyException();

        verify(service).purgeExpiredData(nextStartedAt);
        verifyNoMoreInteractions(service);
    }



    private static SensorDataRetentionRunResult noWorkResult(Instant currentTime) {
        Instant rawBoundary = currentTime.minus(POLICY.getRawRetention()).truncatedTo(ChronoUnit.HOURS);
        Instant hourlyBoundary = currentTime.minus(POLICY.getHourlyRetention()).truncatedTo(ChronoUnit.HOURS);
        Instant dailyBoundary = currentTime.minus(POLICY.getDailyRetention());

        return new SensorDataRetentionRunResult(
                SensorDataRetentionRunResult.Status.NO_WORK,0,
                noWorkTier(Tier.RAW_READINGS,rawBoundary),
                noWorkTier(Tier.HOURLY_SUMMARIES,hourlyBoundary),
                noWorkTier(Tier.DAILY_SUMMARIES,dailyBoundary),
                null,null);
    }



    private static SensorDataPurgeTierResult noWorkTier(Tier tier,Instant boundary) {
        return new SensorDataPurgeTierResult(
                tier,SensorDataPurgeTierResult.Status.NO_WORK,boundary,
                0,0,0,0,0,0,0,0,0,false);
    }
}