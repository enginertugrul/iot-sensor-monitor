package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.RollupCandidateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult.Status.BOUNDED;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult.Status.NO_WORK;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult.Status.PARTIAL_FAILURE;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.hourly.HourlyRollupRunResult.Status.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class HourlySensorRollupServiceTest {

    private static final Instant START = Instant.parse("2026-01-15T10:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final Instant CUTOFF = END.plusSeconds(3600);

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    @Mock
    private HourlySensorRollupBucketProcessor bucketProcessor;

    @Mock
    private SensorDataLifecyclePolicy lifecyclePolicy;

    private HourlySensorRollupService service;

    @BeforeEach
    void setUp() {
        service = new HourlySensorRollupService(sensorRepository,checkpointRepository,bucketProcessor,lifecyclePolicy);
    }



    @Test
    void rejectsNullAndUnalignedCutoffsBeforeConsultingDependencies() {
        assertThatThrownBy(() -> service.rollUpClosedHours(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("eligibleCoveredUntil must not be null");

        assertThatThrownBy(() -> service.rollUpClosedHours(CUTOFF.plusNanos(1)))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("eligibleCoveredUntil must be aligned to a UTC hour");

        verifyNoInteractions(sensorRepository,checkpointRepository,bucketProcessor,lifecyclePolicy);
    }



    @Test
    void reportsNoWorkWhenThereAreNoCandidates() {
        prepareRun(3,null);

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(NO_WORK,0,0,0,0,0,false,null));
        verifyNoInteractions(bucketProcessor);
    }



    @Test
    void skipsCandidatesWhoseCheckpointOrFirstReadingHourIsAlreadyAtOrBeyondCutoff() {
        SensorSnapshot atCutoff = new SensorSnapshot(1L,START,CUTOFF);
        SensorSnapshot firstReadingInOpenHour = new SensorSnapshot(2L,CUTOFF.plusSeconds(1800),null);
        SensorSnapshot beyondCutoff = new SensorSnapshot(3L,START,CUTOFF.plusSeconds(3600));
        prepareRun(1,CUTOFF,atCutoff,firstReadingInOpenHour,beyondCutoff);

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(NO_WORK,3,0,0,0,0,false,CUTOFF));
        verifyNoInteractions(bucketProcessor);
    }



    @Test
    void catchesUpCandidatesInRoundsAndAccumulatesOnlySummarizedSourceRows() {
        SensorSnapshot first = new SensorSnapshot(1L,START.plusSeconds(900),null);
        SensorSnapshot second = new SensorSnapshot(2L,START,START);
        prepareRun(8,CUTOFF,first,second);

        when(bucketProcessor.advanceNextClosedHour(first,CUTOFF))
                .thenReturn(advanced(first,START,2),advanced(first,END,3));
        when(bucketProcessor.advanceNextClosedHour(second,CUTOFF))
                .thenReturn(advanced(second,START,4),advanced(second,END,0));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(SUCCEEDED,2,4,4,9,0,false,CUTOFF));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(second,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(second,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void stopsAtTheSharedBudgetWhilePreservingRoundRobinProgress() {
        SensorSnapshot first = new SensorSnapshot(1L,START,START);
        SensorSnapshot second = new SensorSnapshot(2L,START,START);
        prepareRun(3,END,first,second);

        when(bucketProcessor.advanceNextClosedHour(first,CUTOFF))
                .thenReturn(advanced(first,START,2),advanced(first,END,3));
        when(bucketProcessor.advanceNextClosedHour(second,CUTOFF))
                .thenReturn(advanced(second,START,4));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(BOUNDED,2,3,3,9,0,true,END));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(second,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void reportsBoundedWhenTheBudgetLeavesAnotherCandidateUnattempted() {
        SensorSnapshot first = new SensorSnapshot(1L,START,END);
        SensorSnapshot second = new SensorSnapshot(2L,START,START);
        prepareRun(1,START,first,second);
        when(bucketProcessor.advanceNextClosedHour(first,CUTOFF)).thenReturn(advanced(first,END,1));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(BOUNDED,2,1,1,1,0,true,START));
        verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @Test
    void reportsSuccessWhenTheLastBudgetedBucketCompletesAllPendingWork() {
        SensorSnapshot sensor = new SensorSnapshot(1L,START,END);
        prepareRun(1,CUTOFF,sensor);
        when(bucketProcessor.advanceNextClosedHour(sensor,CUTOFF)).thenReturn(advanced(sensor,END,2));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(SUCCEEDED,1,1,1,2,0,false,CUTOFF));
        verify(bucketProcessor).advanceNextClosedHour(sensor,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @Test
    void reportsNoWorkWhenAStaleCandidateIsAlreadyUpToDate() {
        SensorSnapshot sensor = new SensorSnapshot(1L,START,START);
        prepareRun(1,CUTOFF,sensor);
        when(bucketProcessor.advanceNextClosedHour(sensor,CUTOFF)).thenReturn(upToDate(sensor));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(NO_WORK,1,0,0,0,0,false,CUTOFF));
        verify(bucketProcessor).advanceNextClosedHour(sensor,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @Test
    void doesNotSpendBudgetOnUpToDateResultsButCountsAnAdvancedEmptyBucket() {
        SensorSnapshot alreadyFinished = new SensorSnapshot(1L,START,START);
        SensorSnapshot pending = new SensorSnapshot(2L,START,END);
        prepareRun(1,CUTOFF,alreadyFinished,pending);

        when(bucketProcessor.advanceNextClosedHour(alreadyFinished,CUTOFF)).thenReturn(upToDate(alreadyFinished));
        when(bucketProcessor.advanceNextClosedHour(pending,CUTOFF)).thenReturn(advanced(pending,END,0));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(SUCCEEDED,2,1,1,0,0,false,CUTOFF));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedHour(alreadyFinished,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(pending,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void retainsEarlierProgressAndContinuesOtherSensorsAfterABucketFailure() {
        SensorSnapshot failing = new SensorSnapshot(1L,START,START);
        SensorSnapshot healthy = new SensorSnapshot(2L,START,START);
        prepareRun(10,END,failing,healthy);

        when(bucketProcessor.advanceNextClosedHour(failing,CUTOFF))
                .thenReturn(advanced(failing,START,2))
                .thenThrow(new IllegalStateException("Second bucket failed"));
        when(bucketProcessor.advanceNextClosedHour(healthy,CUTOFF))
                .thenReturn(advanced(healthy,START,4),advanced(healthy,END,3));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(PARTIAL_FAILURE,2,4,3,9,1,false,END));

        InOrder calls = inOrder(bucketProcessor);
        calls.verify(bucketProcessor).advanceNextClosedHour(failing,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(healthy,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(failing,CUTOFF);
        calls.verify(bucketProcessor).advanceNextClosedHour(healthy,CUTOFF);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void countsFailureAgainstTheBudgetAndGivesPartialFailureStatusPrecedence() {
        SensorSnapshot failing = new SensorSnapshot(1L,START,START);
        SensorSnapshot unattempted = new SensorSnapshot(2L,START,START);
        prepareRun(1,START,failing,unattempted);

        when(bucketProcessor.advanceNextClosedHour(failing,CUTOFF))
                .thenThrow(new IllegalStateException("Bucket failed"));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(PARTIAL_FAILURE,2,1,0,0,1,true,START));
        verify(bucketProcessor).advanceNextClosedHour(failing,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    @Test
    void countsEachFailedSensorOnceAndDoesNotRetryItDuringTheSameRun() {
        SensorSnapshot first = new SensorSnapshot(1L,START,START);
        SensorSnapshot second = new SensorSnapshot(2L,START,START);
        prepareRun(2,START,first,second);

        when(bucketProcessor.advanceNextClosedHour(first,CUTOFF))
                .thenThrow(new IllegalStateException("First sensor failed"));
        when(bucketProcessor.advanceNextClosedHour(second,CUTOFF))
                .thenThrow(new IllegalStateException("Second sensor failed"));

        HourlyRollupRunResult result = service.rollUpClosedHours(CUTOFF);

        assertThat(result).isEqualTo(new HourlyRollupRunResult(PARTIAL_FAILURE,2,2,0,0,2,false,START));
        verify(bucketProcessor).advanceNextClosedHour(first,CUTOFF);
        verify(bucketProcessor).advanceNextClosedHour(second,CUTOFF);
        verifyNoMoreInteractions(bucketProcessor);
    }



    private void prepareRun(int budget,Instant oldestCoveredUntil,RollupCandidateProjection... sensors) {
        when(sensorRepository.findSensorsForRollup()).thenReturn(List.of(sensors));
        when(lifecyclePolicy.getMaximumBucketsPerRun()).thenReturn(budget);
        when(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY))
                .thenReturn(Optional.ofNullable(oldestCoveredUntil));
    }



    private static HourlyRollupBucketResult advanced(RollupCandidateProjection sensor,Instant bucketStart,long samples) {
        Instant bucketEnd = bucketStart.plusSeconds(3600);
        return new HourlyRollupBucketResult(HourlyRollupBucketResult.Status.ADVANCED,sensor.getId(),bucketStart,bucketEnd,START,bucketEnd,samples);
    }



    private static HourlyRollupBucketResult upToDate(RollupCandidateProjection sensor) {
        return new HourlyRollupBucketResult(HourlyRollupBucketResult.Status.UP_TO_DATE,sensor.getId(),null,null,START,CUTOFF,0);
    }



    private record SensorSnapshot(Long id,Instant firstReadingAt,Instant hourlyCoveredUntil) implements RollupCandidateProjection {

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public String getTimezone() {
            return "UTC";
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
            return null;
        }
    }
}