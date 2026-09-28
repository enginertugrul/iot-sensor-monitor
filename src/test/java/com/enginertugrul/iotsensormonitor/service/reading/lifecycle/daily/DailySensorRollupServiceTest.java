package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupCandidateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult.Status.BOUNDED;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult.Status.NO_WORK;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult.Status.PARTIAL_FAILURE;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult.Status.SUCCEEDED;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.daily.DailyRollupRunResult.Status.WAITING_FOR_HOURLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class DailySensorRollupServiceTest {

    private static final Instant START = Instant.parse("2026-01-13T00:00:00Z");
    private static final Instant END = START.plusSeconds(86400);
    private static final Instant CUTOFF = END.plusSeconds(86400);

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    @Mock
    private DailySensorRollupBucketProcessor bucketProcessor;

    @Mock
    private SensorDataLifecyclePolicy lifecyclePolicy;

    private DailySensorRollupService service;

    @BeforeEach
    void setUp() {
        service = new DailySensorRollupService(sensorRepository,checkpointRepository,bucketProcessor,lifecyclePolicy);
    }



    @Test
    void rejectsNullCutoffBeforeConsultingDependencies() {
        assertThatThrownBy(() -> service.rollUpClosedLocalDays(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("eligibleBucketEnd must not be null");

        verifyNoInteractions(sensorRepository,checkpointRepository,bucketProcessor,lifecyclePolicy);
    }



    @Test
    void reportsNoWorkWhenThereAreNoCandidates() {
        prepareRun(3,null);

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(NO_WORK,0,0,0,0,0,0,0,0,false,Duration.ZERO,null));
        verifyNoInteractions(bucketProcessor);
    }



    @Test
    void skipsDaysThatHaveNotClosedEvenWhenHourlyCoverageIsMissing() {
        SensorSnapshot existingCheckpoint = new SensorSnapshot(1L,"UTC",START,null,END);
        SensorSnapshot firstReadingInOpenDay = new SensorSnapshot(2L,"UTC",END.plusSeconds(900),null,null);
        SensorSnapshot beyondCutoff = new SensorSnapshot(3L,"UTC",START,CUTOFF,CUTOFF);
        prepareRun(1,END,existingCheckpoint,firstReadingInOpenDay,beyondCutoff);

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF.minusNanos(1));

        assertThat(result).isEqualTo(new DailyRollupRunResult(NO_WORK,3,0,0,0,0,0,0,0,false,Duration.ZERO,END));
        verifyNoInteractions(bucketProcessor);
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-13T00:00:00Z,2026-01-14T00:00:00Z",
            "Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z",
            "Europe/Berlin,2025-03-29T23:00:00Z,2025-03-30T22:00:00Z",
            "Europe/Berlin,2025-10-25T22:00:00Z,2025-10-26T23:00:00Z"
    })
    void waitsForMissingOrInsufficientHourlyCoverageWithoutSpendingBudget(String zoneId,String startText,String endText) {
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        Instant insufficientCoverage = utcHourAtOrAfter(end).minusSeconds(3600);
        SensorSnapshot missing = new SensorSnapshot(1L,zoneId,start.plusSeconds(900),null,null);
        SensorSnapshot behind = new SensorSnapshot(2L,zoneId,start.plusSeconds(900),insufficientCoverage,null);
        prepareRun(1,null,missing,behind);

        DailyRollupRunResult result = service.rollUpClosedLocalDays(end);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                WAITING_FOR_HOURLY,2,0,0,0,0,0,2,0,false,Duration.between(start,end),null));
        verifyNoInteractions(bucketProcessor);
    }



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-13T00:00:00Z,2026-01-14T00:00:00Z,2026-01-14T00:00:00Z,24,0",
            "Asia/Kathmandu,2026-01-12T18:15:00Z,2026-01-13T18:15:00Z,2026-01-13T19:00:00Z,23,1",
            "Europe/Berlin,2025-03-29T23:00:00Z,2025-03-30T22:00:00Z,2025-03-30T22:00:00Z,23,0",
            "Europe/Berlin,2025-10-25T22:00:00Z,2025-10-26T23:00:00Z,2025-10-26T23:00:00Z,25,0"
    })
    void processesAnExactlyClosedLocalDayWhenHourlyCoverageJustReachesItsPrerequisite(
            String zoneId,String startText,String endText,String hourlyEndText,int hourlyRows,long rawRows) {
        Instant start = Instant.parse(startText);
        Instant end = Instant.parse(endText);
        SensorSnapshot sensor = new SensorSnapshot(1L,zoneId,start.plusSeconds(900),Instant.parse(hourlyEndText),null);
        prepareRun(1,end,sensor);
        when(bucketProcessor.advanceNextClosedDay(sensor,end)).thenReturn(advanced(sensor,start,2,hourlyRows,rawRows));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(end);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                SUCCEEDED,1,1,1,2,hourlyRows,rawRows,0,0,false,Duration.between(start,end),end));
        verify(bucketProcessor).advanceNextClosedDay(sensor,end);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @ParameterizedTest
    @CsvSource({
            "3,BOUNDED,3,72,true,2026-01-14T00:00:00Z",
            "4,SUCCEEDED,4,96,false,2026-01-15T00:00:00Z"
    })
    void catchesUpInRoundsAndStopsAtTheSharedBudget(int budget,DailyRollupRunResult.Status status,int advancedBuckets,long hourlyRows,boolean bounded,String oldestText) {

        Instant oldest = Instant.parse(oldestText);
        SensorSnapshot first = new SensorSnapshot(1L,"UTC",START.plusSeconds(900),CUTOFF,null);
        SensorSnapshot second = new SensorSnapshot(2L,"UTC",START,CUTOFF,START);
        prepareRun(budget,oldest,first,second);

        when(bucketProcessor.advanceNextClosedDay(first,CUTOFF))
                .thenReturn(advanced(first,START,2,24,0),advanced(first,END,3,24,0));
        when(bucketProcessor.advanceNextClosedDay(second,CUTOFF))
                .thenReturn(advanced(second,START,4,24,0),advanced(second,END,0,24,0));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                status,2,advancedBuckets,advancedBuckets,9,hourlyRows,0,0,0,bounded,Duration.ofDays(2),oldest));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedDay(first,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(second,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(first,CUTOFF);
        if (advancedBuckets == 4) {
            calls.verify(bucketProcessor).advanceNextClosedDay(second,CUTOFF);
        }
        calls.verifyNoMoreInteractions();
    }



    @Test
    void doesNotSpendBudgetOnUpToDateResultsButCountsAnAdvancedEmptyDay() {
        SensorSnapshot stale = new SensorSnapshot(1L,"UTC",START,CUTOFF,START);
        SensorSnapshot pending = new SensorSnapshot(2L,"UTC",START,CUTOFF,END);
        prepareRun(1,CUTOFF,stale,pending);

        when(bucketProcessor.advanceNextClosedDay(stale,CUTOFF))
                .thenReturn(bucketResult(stale,DailyRollupBucketResult.Status.UP_TO_DATE,CUTOFF,0,0,0));
        when(bucketProcessor.advanceNextClosedDay(pending,CUTOFF)).thenReturn(advanced(pending,END,0,24,0));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                SUCCEEDED,2,1,1,0,24,0,0,0,false,Duration.ofDays(2),CUTOFF));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedDay(stale,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(pending,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void stopsRetryingAWaitingSensorAndPreservesEarlierProgress() {
        SensorSnapshot waiting = new SensorSnapshot(1L,"UTC",START,END,null);
        SensorSnapshot healthy = new SensorSnapshot(2L,"UTC",START,CUTOFF,END);
        prepareRun(3,END,waiting,healthy);

        when(bucketProcessor.advanceNextClosedDay(waiting,CUTOFF))
                .thenReturn(advanced(waiting,START,2,24,0))
                .thenReturn(bucketResult(waiting,DailyRollupBucketResult.Status.WAITING_FOR_HOURLY,END,0,0,0));
        when(bucketProcessor.advanceNextClosedDay(healthy,CUTOFF)).thenReturn(advanced(healthy,END,3,24,0));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                WAITING_FOR_HOURLY,2,2,2,5,48,0,1,0,false,Duration.ofDays(2),END));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedDay(waiting,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(healthy,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(waiting,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void givesBoundedStatusPrecedenceOverWaitingWhenEligibleWorkRemains() {
        SensorSnapshot waiting = new SensorSnapshot(1L,"UTC",START,null,START);
        SensorSnapshot pending = new SensorSnapshot(2L,"UTC",START,CUTOFF,START);
        prepareRun(1,START,waiting,pending);
        when(bucketProcessor.advanceNextClosedDay(pending,CUTOFF)).thenReturn(advanced(pending,START,2,24,0));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                BOUNDED,2,1,1,2,24,0,1,0,true,Duration.ofDays(2),START));
        verify(bucketProcessor).advanceNextClosedDay(pending,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @Test
    void isolatesABucketFailureAndContinuesProcessingOtherSensors() {
        SensorSnapshot failing = new SensorSnapshot(1L,"UTC",START,CUTOFF,START);
        SensorSnapshot healthy = new SensorSnapshot(2L,"UTC",START,CUTOFF,START);
        prepareRun(10,END,failing,healthy);

        when(bucketProcessor.advanceNextClosedDay(failing,CUTOFF))
                .thenReturn(advanced(failing,START,2,24,0))
                .thenThrow(new IllegalStateException("Second daily bucket failed"));
        when(bucketProcessor.advanceNextClosedDay(healthy,CUTOFF))
                .thenReturn(advanced(healthy,START,4,24,0),advanced(healthy,END,3,24,0));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                PARTIAL_FAILURE,2,4,3,9,72,0,0,1,false,Duration.ofDays(2),END));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedDay(failing,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(healthy,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(failing,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedDay(healthy,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void countsFailureAgainstTheBudgetAndGivesItPrecedenceOverBoundedAndWaitingStatuses() {
        SensorSnapshot failing = new SensorSnapshot(1L,"UTC",START,CUTOFF,START);
        SensorSnapshot unattempted = new SensorSnapshot(2L,"UTC",START,CUTOFF,START);
        SensorSnapshot waiting = new SensorSnapshot(3L,"UTC",START,null,START);
        prepareRun(1,START,failing,unattempted,waiting);

        when(bucketProcessor.advanceNextClosedDay(failing,CUTOFF))
                .thenThrow(new IllegalStateException("Daily bucket failed"));

        DailyRollupRunResult result = service.rollUpClosedLocalDays(CUTOFF);

        assertThat(result).isEqualTo(new DailyRollupRunResult(
                PARTIAL_FAILURE,3,1,0,0,0,0,1,1,true,Duration.ofDays(2),START));
        verify(bucketProcessor).advanceNextClosedDay(failing,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    private void prepareRun(int budget,Instant oldestCoveredUntil,RollupCandidateProjection... sensors) {
        when(sensorRepository.findSensorsForRollup()).thenReturn(List.of(sensors));
        when(lifecyclePolicy.getMaximumBucketsPerRun()).thenReturn(budget);
        when(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY))
                .thenReturn(Optional.ofNullable(oldestCoveredUntil));
    }



    private static DailyRollupBucketResult advanced(SensorSnapshot sensor,Instant start,long samples,int hourlyRows,long rawRows) {
        return bucketResult(sensor,DailyRollupBucketResult.Status.ADVANCED,start,samples,hourlyRows,rawRows);
    }



    private static DailyRollupBucketResult bucketResult(
            SensorSnapshot sensor,DailyRollupBucketResult.Status status,Instant start,long samples,int hourlyRows,long rawRows) {
        ZoneId zone = ZoneId.of(sensor.getTimezone());
        LocalDate date = start.atZone(zone).toLocalDate();
        Instant end = date.plusDays(1).atStartOfDay(zone).toInstant();
        Instant coverageStart = sensor.getFirstReadingAt().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        Instant coveredUntil = status == DailyRollupBucketResult.Status.ADVANCED ? end : start;

        return new DailyRollupBucketResult(
                status,sensor.getId(),date,zone.getId(),start,end,coverageStart,coveredUntil,
                utcHourAtOrAfter(end),sensor.getHourlyCoveredUntil(),samples,hourlyRows,rawRows);
    }



    private static Instant utcHourAtOrAfter(Instant value) {
        Instant floor = value.truncatedTo(ChronoUnit.HOURS);
        return value.equals(floor) ? floor : floor.plusSeconds(3600);
    }



    private record SensorSnapshot(
            Long id,String timezone,Instant firstReadingAt,Instant hourlyCoveredUntil,Instant dailyCoveredUntil)
            implements RollupCandidateProjection {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getTimezone() {
            return timezone;
        }

        @Override
        public Instant getFirstReadingAt() {
            return firstReadingAt;
        }

        @Override
        public Instant getHourlyCoveredUntil() {
            return hourlyCoveredUntil;
        }

        @Override
        public Instant getDailyCoveredUntil() {
            return dailyCoveredUntil;
        }
    }
}