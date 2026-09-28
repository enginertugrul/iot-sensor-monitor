package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.SensorDataLifecyclePolicy;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.CoverageBlocker;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeTierResult.Tier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deferredByConcurrentWork;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deleted;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.noExpiredRows;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.waitingForCoverage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorDataRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T12:34:56.123456Z");
    private static final Instant RAW_CUTOFF = Instant.parse("2026-01-13T12:00:00Z");
    private static final Instant HOURLY_CUTOFF = Instant.parse("2026-01-11T12:00:00Z");
    private static final Instant DAILY_CUTOFF = Instant.parse("2026-01-07T12:34:56.123456Z");
    private static final int BATCH_SIZE = 2;

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    @Mock
    private SensorDataPurgeBatchProcessor batchProcessor;

    private SensorDataRetentionService service;


    @BeforeEach
    void setUp() {
        service = new SensorDataRetentionService(sensorRepository,checkpointRepository,batchProcessor,policy(3));
    }



    @Test
    void rejectsNullTimeBeforeConsultingDependencies() {
        assertThatThrownBy(() -> service.purgeExpiredData(null))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("currentTime must not be null");

        verifyNoInteractions(sensorRepository,checkpointRepository,batchProcessor);
    }



    @Test
    void reportsNoWorkWithCorrectCutoffsWhenThereAreNoSensors() {
        prepareRun(3);

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result).isEqualTo(new SensorDataRetentionRunResult(
                SensorDataRetentionRunResult.Status.NO_WORK,0,
                new SensorDataPurgeTierResult(
                        Tier.RAW_READINGS,SensorDataPurgeTierResult.Status.NO_WORK,RAW_CUTOFF,
                        0,0,0,0,0,0,0,0,0,false),
                new SensorDataPurgeTierResult(
                        Tier.HOURLY_SUMMARIES,SensorDataPurgeTierResult.Status.NO_WORK,HOURLY_CUTOFF,
                        0,0,0,0,0,0,0,0,0,false),
                new SensorDataPurgeTierResult(
                        Tier.DAILY_SUMMARIES,SensorDataPurgeTierResult.Status.NO_WORK,DAILY_CUTOFF,
                        0,0,0,0,0,0,0,0,0,false),
                null,null));

        verifyNoInteractions(batchProcessor);
    }



    @Test
    void PassesExactCutoffsAndBatchSizeAndIncludesCheckpointProgress() {
        prepareRun(3,1L);
        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE)).thenReturn(noExpiredRows(1L,false));
        stubOtherTiersWithoutExpiredRows(1L);
        when(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.RAW_TO_HOURLY))
                .thenReturn(Optional.of(RAW_CUTOFF));
        when(checkpointRepository.findOldestCoveredUntilByStage(RollupStage.HOURLY_TO_DAILY))
                .thenReturn(Optional.of(HOURLY_CUTOFF));

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.NO_WORK);
        assertThat(result.sensorCount()).isEqualTo(1);
        assertThat(result.totalOperationsAttempted()).isEqualTo(3);
        assertThat(result.totalDeleteBatchesAttempted()).isZero();
        assertThat(result.oldestRawToHourlyCoveredUntil()).isEqualTo(RAW_CUTOFF);
        assertThat(result.oldestHourlyToDailyCoveredUntil()).isEqualTo(HOURLY_CUTOFF);

        verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        verify(batchProcessor).purgeHourlySummaries(1L,HOURLY_CUTOFF,BATCH_SIZE);
        verify(batchProcessor).purgeDailySummaries(1L,DAILY_CUTOFF,BATCH_SIZE);
    }



    @ParameterizedTest
    @ValueSource(ints = {3,4})
    void processesSensorsInRoundsAndStopsExactlyAtTheTierBudget(int budget) {
        prepareRun(budget,1L,2L);
        stubOtherTiersWithoutExpiredRows(1L,2L);

        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(1L,2,true,CoverageBlocker.NONE),deleted(1L,1,false,CoverageBlocker.NONE));
        when(batchProcessor.purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(2L,2,true,CoverageBlocker.NONE),deleted(2L,1,false,CoverageBlocker.NONE));

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        boolean bounded = budget == 3;
        SensorDataPurgeTierResult.Status tierStatus = bounded
                ? SensorDataPurgeTierResult.Status.BOUNDED
                : SensorDataPurgeTierResult.Status.SUCCEEDED;

        assertThat(result.rawReadings()).isEqualTo(new SensorDataPurgeTierResult(
                Tier.RAW_READINGS,tierStatus,RAW_CUTOFF,budget,budget,budget,bounded ? 5 : 6,
                0,0,0,0,0,bounded));
        assertThat(result.status()).isEqualTo(bounded
                ? SensorDataRetentionRunResult.Status.BOUNDED
                : SensorDataRetentionRunResult.Status.SUCCEEDED);

        InOrder calls = inOrder(batchProcessor);
        calls.verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        if (!bounded) {
            calls.verify(batchProcessor).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
        }
        calls.verify(batchProcessor).purgeHourlySummaries(2L,HOURLY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeHourlySummaries(1L,HOURLY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeDailySummaries(1L,DAILY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeDailySummaries(2L,DAILY_CUTOFF,BATCH_SIZE);
        calls.verifyNoMoreInteractions();
    }



    @ParameterizedTest
    @CsvSource({
            "0,1,2,3",
            "1,2,3,1",
            "2,3,1,2"
    })
    void rotatesTheStartingSensorAcrossRunsAndGivesEveryTierItsOwnBudget(
            long hourOffset,long firstRaw,long firstHourly,long firstDaily) {
        prepareRun(1,1L,2L,3L);
        Instant currentTime = NOW.plusSeconds(hourOffset * 3600);

        when(batchProcessor.purgeRawReadings(anyLong(),any(Instant.class),eq(BATCH_SIZE)))
                .thenAnswer(invocation -> deleted(invocation.getArgument(0,Long.class),1,true,CoverageBlocker.NONE));
        when(batchProcessor.purgeHourlySummaries(anyLong(),any(Instant.class),eq(BATCH_SIZE)))
                .thenAnswer(invocation -> deleted(invocation.getArgument(0,Long.class),1,true,CoverageBlocker.NONE));
        when(batchProcessor.purgeDailySummaries(anyLong(),any(Instant.class),eq(BATCH_SIZE)))
                .thenAnswer(invocation -> deleted(invocation.getArgument(0,Long.class),1,true,CoverageBlocker.NONE));

        SensorDataRetentionRunResult result = service.purgeExpiredData(currentTime);

        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.BOUNDED);
        assertThat(result.sensorCount()).isEqualTo(3);
        assertThat(result.totalOperationsAttempted()).isEqualTo(3);
        assertThat(result.totalDeleteBatchesAttempted()).isEqualTo(3);
        assertThat(result.totalDeletionBatches()).isEqualTo(3);
        assertThat(result.totalRowsDeleted()).isEqualTo(3);

        for (SensorDataPurgeTierResult tier : List.of(
                result.rawReadings(),result.hourlySummaries(),result.dailySummaries())) {
            assertThat(tier.status()).isEqualTo(SensorDataPurgeTierResult.Status.BOUNDED);
            assertThat(tier.operationsAttempted()).isEqualTo(1);
            assertThat(tier.deleteBatchesAttempted()).isEqualTo(1);
            assertThat(tier.rowsDeleted()).isEqualTo(1);
            assertThat(tier.bounded()).isTrue();
        }

        InOrder calls = inOrder(batchProcessor);
        calls.verify(batchProcessor).purgeRawReadings(firstRaw,RAW_CUTOFF.plusSeconds(hourOffset * 3600),BATCH_SIZE);
        calls.verify(batchProcessor).purgeHourlySummaries(firstHourly,HOURLY_CUTOFF.plusSeconds(hourOffset * 3600),BATCH_SIZE);
        calls.verify(batchProcessor).purgeDailySummaries(firstDaily,DAILY_CUTOFF.plusSeconds(hourOffset * 3600),BATCH_SIZE);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void doesNotSpendDeleteBudgetOnExpiryChecksOrCoverageWaitsWithoutADeleteAttempt() {
        prepareRun(1,1L,2L,3L);
        stubOtherTiersWithoutExpiredRows(1L,2L,3L);
        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE)).thenReturn(noExpiredRows(1L,false));
        when(batchProcessor.purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(waitingForCoverage(2L,false,CoverageBlocker.BOTH));
        when(batchProcessor.purgeRawReadings(3L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(3L,2,false,CoverageBlocker.NONE));

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result.rawReadings()).isEqualTo(new SensorDataPurgeTierResult(
                Tier.RAW_READINGS,SensorDataPurgeTierResult.Status.SUCCEEDED,RAW_CUTOFF,
                3,1,1,2,1,1,1,0,0,false));
        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.SUCCEEDED);
        assertThat(result.totalDeleteBatchesAttempted()).isEqualTo(1);

        InOrder calls = inOrder(batchProcessor);
        calls.verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(3L,RAW_CUTOFF,BATCH_SIZE);
    }



    @ParameterizedTest
    @EnumSource(value = SensorDataPurgeBatchResult.Status.class,names = {
            "NO_EXPIRED_ROWS","WAITING_FOR_COVERAGE","DEFERRED_BY_CONCURRENT_WORK"
    })
    void spendsBudgetOnDeleteAttemptsEvenWhenTheyDeleteNoRows(SensorDataPurgeBatchResult.Status status) {
        prepareRun(1,1L,2L);
        stubOtherTiersWithoutExpiredRows(1L,2L);

        SensorDataPurgeBatchResult batchResult = switch (status) {
            case NO_EXPIRED_ROWS -> noExpiredRows(1L,true);
            case WAITING_FOR_COVERAGE -> waitingForCoverage(1L,true,CoverageBlocker.BOTH);
            case DEFERRED_BY_CONCURRENT_WORK -> deferredByConcurrentWork(1L);
            default -> throw new IllegalArgumentException("Unsupported test status");
        };
        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE)).thenReturn(batchResult);

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        int noExpired = status == SensorDataPurgeBatchResult.Status.NO_EXPIRED_ROWS ? 1 : 0;
        int waiting = status == SensorDataPurgeBatchResult.Status.WAITING_FOR_COVERAGE ? 1 : 0;
        int deferred = status == SensorDataPurgeBatchResult.Status.DEFERRED_BY_CONCURRENT_WORK ? 1 : 0;

        assertThat(result.rawReadings()).isEqualTo(new SensorDataPurgeTierResult(
                Tier.RAW_READINGS,SensorDataPurgeTierResult.Status.BOUNDED,RAW_CUTOFF,
                1,1,0,0,noExpired,waiting,waiting,deferred,0,true));
        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.BOUNDED);

        verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        verify(batchProcessor,never()).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
    }



    @ParameterizedTest
    @CsvSource({
            "false,false,WAITING_FOR_COVERAGE",
            "false,true,RETRY_PENDING",
            "true,true,SUCCEEDED"
    })
    void reportsWaitingDeferralAndPartialDeletionWithTheExpectedStatusPrecedence(
            boolean deletedRows,boolean deferred,SensorDataRetentionRunResult.Status expectedStatus) {
        prepareRun(3,1L);

        SensorDataPurgeBatchResult rawResult = deletedRows
                ? deleted(1L,2,false,CoverageBlocker.BOTH)
                : waitingForCoverage(1L,false,CoverageBlocker.BOTH);
        SensorDataPurgeBatchResult dailyResult = deferred
                ? deferredByConcurrentWork(1L)
                : noExpiredRows(1L,false);

        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE)).thenReturn(rawResult);
        when(batchProcessor.purgeHourlySummaries(1L,HOURLY_CUTOFF,BATCH_SIZE))
                .thenReturn(waitingForCoverage(1L,false,CoverageBlocker.HOURLY_TO_DAILY));
        when(batchProcessor.purgeDailySummaries(1L,DAILY_CUTOFF,BATCH_SIZE)).thenReturn(dailyResult);

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.totalOperationsAttempted()).isEqualTo(3);
        assertThat(result.totalDeleteBatchesAttempted()).isEqualTo((deletedRows ? 1 : 0) + (deferred ? 1 : 0));
        assertThat(result.totalDeletionBatches()).isEqualTo(deletedRows ? 1 : 0);
        assertThat(result.totalRowsDeleted()).isEqualTo(deletedRows ? 2 : 0);
        assertThat(result.rawReadings().waitingForHourlyCoverageSensors()).isEqualTo(1);
        assertThat(result.rawReadings().waitingForDailyCoverageSensors()).isEqualTo(1);
        assertThat(result.hourlySummaries().waitingForDailyCoverageSensors()).isEqualTo(1);
        assertThat(result.dailySummaries().concurrentlyDeferredSensors()).isEqualTo(deferred ? 1 : 0);

        verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        verify(batchProcessor).purgeHourlySummaries(1L,HOURLY_CUTOFF,BATCH_SIZE);
        verify(batchProcessor).purgeDailySummaries(1L,DAILY_CUTOFF,BATCH_SIZE);
    }



    @Test
    void preservesEarlierProgressAndContinuesOtherSensorsAndTiersAfterFailure() {
        prepareRun(4,1L,2L);
        stubOtherTiersWithoutExpiredRows(1L,2L);

        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(1L,2,true,CoverageBlocker.NONE))
                .thenThrow(new IllegalStateException("Second raw batch failed"));
        when(batchProcessor.purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(2L,1,false,CoverageBlocker.NONE));

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result.rawReadings()).isEqualTo(new SensorDataPurgeTierResult(
                Tier.RAW_READINGS,SensorDataPurgeTierResult.Status.PARTIAL_FAILURE,RAW_CUTOFF,
                3,2,2,3,0,0,0,0,1,false));
        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.PARTIAL_FAILURE);
        assertThat(result.totalOperationsAttempted()).isEqualTo(7);
        assertThat(result.totalDeleteBatchesAttempted()).isEqualTo(2);
        assertThat(result.totalDeletionBatches()).isEqualTo(2);
        assertThat(result.totalRowsDeleted()).isEqualTo(3);
        assertThat(result.hourlySummaries().noExpiredRowsSensors()).isEqualTo(2);
        assertThat(result.dailySummaries().noExpiredRowsSensors()).isEqualTo(2);

        InOrder calls = inOrder(batchProcessor);
        calls.verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeHourlySummaries(2L,HOURLY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeHourlySummaries(1L,HOURLY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeDailySummaries(1L,DAILY_CUTOFF,BATCH_SIZE);
        calls.verify(batchProcessor).purgeDailySummaries(2L,DAILY_CUTOFF,BATCH_SIZE);
        calls.verifyNoMoreInteractions();
    }



    @Test
    void rejectsResultsForAnotherSensorSpendsBudgetAndPrioritizesFailureOverBoundedStatus() {
        prepareRun(1,1L,2L);
        stubOtherTiersWithoutExpiredRows(1L,2L);
        when(batchProcessor.purgeRawReadings(1L,RAW_CUTOFF,BATCH_SIZE))
                .thenReturn(deleted(999L,2,true,CoverageBlocker.NONE));

        SensorDataRetentionRunResult result = service.purgeExpiredData(NOW);

        assertThat(result.rawReadings()).isEqualTo(new SensorDataPurgeTierResult(
                Tier.RAW_READINGS,SensorDataPurgeTierResult.Status.PARTIAL_FAILURE,RAW_CUTOFF,
                1,0,0,0,0,0,0,0,1,true));
        assertThat(result.status()).isEqualTo(SensorDataRetentionRunResult.Status.PARTIAL_FAILURE);
        assertThat(result.totalRowsDeleted()).isZero();
        assertThat(result.hourlySummaries().operationsAttempted()).isEqualTo(2);
        assertThat(result.dailySummaries().operationsAttempted()).isEqualTo(2);

        verify(batchProcessor,never()).purgeRawReadings(2L,RAW_CUTOFF,BATCH_SIZE);
    }



    private void prepareRun(int budget,Long... sensorIds) {
        service = new SensorDataRetentionService(sensorRepository,checkpointRepository,batchProcessor,policy(budget));
        when(sensorRepository.findSensorIdsWithReadingHistory()).thenReturn(List.of(sensorIds));
    }



    private void stubOtherTiersWithoutExpiredRows(Long... sensorIds) {
        for (Long sensorId : sensorIds) {
            when(batchProcessor.purgeHourlySummaries(sensorId,HOURLY_CUTOFF,BATCH_SIZE))
                    .thenReturn(noExpiredRows(sensorId,false));
            when(batchProcessor.purgeDailySummaries(sensorId,DAILY_CUTOFF,BATCH_SIZE))
                    .thenReturn(noExpiredRows(sensorId,false));
        }
    }



    private SensorDataLifecyclePolicy policy(int budget) {
        return new SensorDataLifecyclePolicy(
                Duration.ofDays(2),Duration.ofDays(4),Duration.ofDays(8),
                Duration.ofMinutes(5),Duration.ofMinutes(5),
                Duration.ofMinutes(15),Duration.ofMinutes(15),
                Duration.ofHours(1),BATCH_SIZE,10,budget);
    }
}