package com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.CoverageBlocker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deferredByConcurrentWork;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.deleted;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.noExpiredRows;
import static com.enginertugrul.iotsensormonitor.service.reading.lifecycle.retention.SensorDataPurgeBatchResult.waitingForCoverage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorDataPurgeBatchProcessorTest {

    private static final long SENSOR_ID = 1L;
    private static final Instant CUTOFF = Instant.parse("2026-01-14T00:00:00Z");
    private static final int BATCH_SIZE = 2;

    @Mock
    private SensorReadingRepository readingRepository;

    @Mock
    private HourlySensorSummaryRepository hourlyRepository;

    @Mock
    private DailySensorSummaryRepository dailyRepository;

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    private SensorDataPurgeBatchProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new SensorDataPurgeBatchProcessor(
                readingRepository,hourlyRepository,dailyRepository,checkpointRepository);
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void rejectsInvalidSensorIdsBeforeConsultingRepositories(Tier tier) {
        assertThatThrownBy(() -> purge(tier,null,CUTOFF,BATCH_SIZE))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("sensorId must not be null");

        for (long sensorId : new long[]{0,-1}) {
            assertThatThrownBy(() -> purge(tier,sensorId,CUTOFF,BATCH_SIZE))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("sensorId must be positive");
        }

        verifyNoInteractions(readingRepository,hourlyRepository,dailyRepository,checkpointRepository);
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void rejectsNullCutoffsAndInvalidBatchSizesBeforeConsultingRepositories(Tier tier) {
        assertThatThrownBy(() -> purge(tier,SENSOR_ID,null,BATCH_SIZE))
                .isExactlyInstanceOf(NullPointerException.class);

        for (int batchSize : new int[]{-1,0,10_001}) {
            assertThatThrownBy(() -> purge(tier,SENSOR_ID,CUTOFF,batchSize))
                    .isExactlyInstanceOf(IllegalArgumentException.class)
                    .hasMessage("batchSize must be between 1 and 10000");
        }

        verifyNoInteractions(readingRepository,hourlyRepository,dailyRepository,checkpointRepository);
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void requiresUtcHourAlignmentForRawAndHourlyCutoffs(Tier tier) {
        assertThatThrownBy(() -> purge(tier,SENSOR_ID,CUTOFF.plusNanos(1),BATCH_SIZE))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be aligned to a UTC hour");

        verifyNoInteractions(readingRepository,hourlyRepository,dailyRepository,checkpointRepository);
    }



    @ParameterizedTest
    @ValueSource(ints = {1,10_000})
    void acceptsBatchSizeEndpointsAndAnUnalignedDailyCutoff(int batchSize) {
        Instant cutoff = CUTOFF.plusSeconds(17).plusNanos(123_000);

        assertThat(processor.purgeDailySummaries(SENSOR_ID,cutoff,batchSize))
                .isEqualTo(noExpiredRows(SENSOR_ID,false));

        verify(dailyRepository).existsBySensorIdAndBucketEndLessThanEqual(SENSOR_ID,cutoff);
        verifyNoInteractions(readingRepository,hourlyRepository,checkpointRepository);
        verifyNoDeletes();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void returnsWithoutCheckingCoverageOrDeletingWhenNoRowsHaveExpired(Tier tier) {
        stubExpired(tier,false);

        assertThat(purge(tier)).isEqualTo(noExpiredRows(SENSOR_ID,false));

        verifyNoInteractions(checkpointRepository);
        verifyNoDeletes();
    }



    @ParameterizedTest
    @CsvSource({
            "false,false,BOTH",
            "false,true,RAW_TO_HOURLY",
            "true,false,HOURLY_TO_DAILY"
    })
    void identifiesMissingRawCoverage(boolean hourlyPresent,boolean dailyPresent,CoverageBlocker blocker) {
        stubExpired(Tier.RAW,true);
        stubCoverage(Tier.RAW,hourlyPresent ? CUTOFF : null,dailyPresent ? CUTOFF : null);

        assertThat(purge(Tier.RAW)).isEqualTo(waitingForCoverage(SENSOR_ID,false,blocker));

        verify(readingRepository,never()).existsEligibleForRetentionPurge(anyLong(),any());
        verifyNoDeletes();
    }



    @Test
    void preservesHourlySummariesWhenDailyCoverageIsMissing() {
        stubExpired(Tier.HOURLY,true);
        stubCoverage(Tier.HOURLY,null,null);

        assertThat(purge(Tier.HOURLY))
                .isEqualTo(waitingForCoverage(SENSOR_ID,false,CoverageBlocker.HOURLY_TO_DAILY));

        verify(hourlyRepository,never()).existsEligibleForRetentionPurge(anyLong(),any());
        verifyNoDeletes();
    }



    @ParameterizedTest
    @CsvSource({
            "-3600,0,RAW_TO_HOURLY",
            "0,-900,HOURLY_TO_DAILY",
            "-3600,-900,BOTH"
    })
    void identifiesLaggingRawCoverageWhenNoSafeRowsAreAvailable(long hourlyOffset,long dailyOffset,CoverageBlocker blocker) {
        stubExpired(Tier.RAW,true);
        stubCoverage(Tier.RAW,CUTOFF.plusSeconds(hourlyOffset),CUTOFF.plusSeconds(dailyOffset));
        stubEligible(Tier.RAW,false);

        assertThat(purge(Tier.RAW)).isEqualTo(waitingForCoverage(SENSOR_ID,false,blocker));

        verifyNoDeletes();
    }



    @Test
    void waitsForDailyCoverageWhenExpiredHourlyRowsAreNotYetSafe() {
        stubExpired(Tier.HOURLY,true);
        stubCoverage(Tier.HOURLY,null,CUTOFF.minusSeconds(900));
        stubEligible(Tier.HOURLY,false);

        assertThat(purge(Tier.HOURLY))
                .isEqualTo(waitingForCoverage(SENSOR_ID,false,CoverageBlocker.HOURLY_TO_DAILY));

        verifyNoDeletes();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void reportsACompletedDeletionBatch(Tier tier) {
        stubExpired(tier,true,false);
        stubCoverage(tier,CUTOFF,CUTOFF);
        if (tier != Tier.DAILY) {
            stubEligible(tier,true,false);
        }
        stubDelete(tier,2);

        assertThat(purge(tier)).isEqualTo(deleted(SENSOR_ID,2,false,CoverageBlocker.NONE));
    }



    @ParameterizedTest
    @CsvSource({
            "RAW,true,true,NONE",
            "RAW,false,true,HOURLY_TO_DAILY",
            "RAW,false,false,NONE",
            "HOURLY,true,true,NONE",
            "HOURLY,false,true,HOURLY_TO_DAILY",
            "HOURLY,false,false,NONE"
    })
    void reportsCoverageBlockersOnlyAfterSafeRowsAreExhausted(
            Tier tier,boolean moreEligibleRows,boolean expiredRowsRemain,CoverageBlocker blocker) {
        stubExpired(tier,true,expiredRowsRemain);
        stubCoverage(tier,CUTOFF,CUTOFF.minusSeconds(900));
        stubEligible(tier,true,moreEligibleRows);
        stubDelete(tier,2);

        assertThat(purge(tier)).isEqualTo(deleted(SENSOR_ID,2,moreEligibleRows,blocker));
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void recognizesRowsRemovedBeforeTheDeleteWasAttempted(Tier tier) {
        stubExpired(tier,true,false);
        stubCoverage(tier,CUTOFF,CUTOFF);
        stubEligible(tier,false);

        assertThat(purge(tier)).isEqualTo(noExpiredRows(SENSOR_ID,false));

        verifyNoDeletes();
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void recognizesRowsRemovedDuringTheDeleteAttempt(Tier tier) {
        stubExpired(tier,true,false);
        stubCoverage(tier,CUTOFF,CUTOFF);
        if (tier != Tier.DAILY) {
            stubEligible(tier,true,false);
        }
        stubDelete(tier,0);

        assertThat(purge(tier)).isEqualTo(noExpiredRows(SENSOR_ID,true));
    }



    @ParameterizedTest
    @EnumSource(Tier.class)
    void defersWhenTheDeleteReturnsZeroButEligibleRowsRemain(Tier tier) {
        stubExpired(tier,true);
        stubCoverage(tier,CUTOFF,CUTOFF);
        if (tier != Tier.DAILY) {
            stubEligible(tier,true);
        }
        stubDelete(tier,0);

        assertThat(purge(tier)).isEqualTo(deferredByConcurrentWork(SENSOR_ID));
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void reportsWaitingAfterAnEmptyDeleteLeavesOnlyUncoveredRows(Tier tier) {
        stubExpired(tier,true);
        stubCoverage(tier,CUTOFF,CUTOFF.minusSeconds(900));
        stubEligible(tier,true,false);
        stubDelete(tier,0);

        assertThat(purge(tier))
                .isEqualTo(waitingForCoverage(SENSOR_ID,true,CoverageBlocker.HOURLY_TO_DAILY));
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void rejectsAnEligibilityContradictionInsideCompleteCoverage(Tier tier) {
        stubExpired(tier,true);
        stubCoverage(tier,CUTOFF,CUTOFF);
        stubEligible(tier,false);

        assertThatThrownBy(() -> purge(tier))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("none are eligible for deletion");

        verifyNoDeletes();
    }



    @ParameterizedTest
    @EnumSource(value = Tier.class,names = {"RAW","HOURLY"})
    void rejectsAnEmptyDeleteWhenExpiredRowsRemainInsideCompleteCoverage(Tier tier) {
        stubExpired(tier,true);
        stubCoverage(tier,CUTOFF,CUTOFF);
        stubEligible(tier,true,false);
        stubDelete(tier,0);

        assertThatThrownBy(() -> purge(tier))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deletion returned no rows");
    }



    @ParameterizedTest
    @CsvSource({
            "RAW,-1",
            "RAW,3",
            "HOURLY,-1",
            "HOURLY,3",
            "DAILY,-1",
            "DAILY,3"
    })
    void rejectsImpossibleDeletedRowCounts(Tier tier,int rowsDeleted) {
        stubExpired(tier,true);
        stubCoverage(tier,CUTOFF,CUTOFF);
        if (tier != Tier.DAILY) {
            stubEligible(tier,true);
        }
        stubDelete(tier,rowsDeleted);

        assertThatThrownBy(() -> purge(tier))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Deleted row count must be between zero and batchSize");
    }



    private SensorDataPurgeBatchResult purge(Tier tier) {
        return purge(tier,SENSOR_ID,CUTOFF,BATCH_SIZE);
    }



    private SensorDataPurgeBatchResult purge(Tier tier,Long sensorId,Instant cutoff,int batchSize) {
        return switch (tier) {
            case RAW -> processor.purgeRawReadings(sensorId,cutoff,batchSize);
            case HOURLY -> processor.purgeHourlySummaries(sensorId,cutoff,batchSize);
            case DAILY -> processor.purgeDailySummaries(sensorId,cutoff,batchSize);
        };
    }



    private void stubExpired(Tier tier,boolean first,Boolean... remaining) {
        switch (tier) {
            case RAW -> when(readingRepository.existsBySensorIdAndRecordedAtBefore(SENSOR_ID,CUTOFF))
                    .thenReturn(first,remaining);
            case HOURLY -> when(hourlyRepository.existsBySensorIdAndBucketEndLessThanEqual(SENSOR_ID,CUTOFF))
                    .thenReturn(first,remaining);
            case DAILY -> when(dailyRepository.existsBySensorIdAndBucketEndLessThanEqual(SENSOR_ID,CUTOFF))
                    .thenReturn(first,remaining);
        }
    }



    private void stubEligible(Tier tier,boolean first,Boolean... remaining) {
        switch (tier) {
            case RAW -> when(readingRepository.existsEligibleForRetentionPurge(SENSOR_ID,CUTOFF))
                    .thenReturn(first,remaining);
            case HOURLY -> when(hourlyRepository.existsEligibleForRetentionPurge(SENSOR_ID,CUTOFF))
                    .thenReturn(first,remaining);
            case DAILY -> throw new IllegalArgumentException("Daily eligibility uses the expiry query");
        }
    }



    private void stubDelete(Tier tier,int rowsDeleted) {
        switch (tier) {
            case RAW -> when(readingRepository.deleteOldestEligibleRetentionBatch(SENSOR_ID,CUTOFF,BATCH_SIZE))
                    .thenReturn(rowsDeleted);
            case HOURLY -> when(hourlyRepository.deleteOldestEligibleRetentionBatch(SENSOR_ID,CUTOFF,BATCH_SIZE))
                    .thenReturn(rowsDeleted);
            case DAILY -> when(dailyRepository.deleteOldestRetentionBatch(SENSOR_ID,CUTOFF,BATCH_SIZE))
                    .thenReturn(rowsDeleted);
        }
    }



    private void stubCoverage(Tier tier,Instant hourlyCoveredUntil,Instant dailyCoveredUntil) {
        if (tier == Tier.RAW) {
            stubCheckpoint(RollupStage.RAW_TO_HOURLY,hourlyCoveredUntil);
        }
        if (tier != Tier.DAILY) {
            stubCheckpoint(RollupStage.HOURLY_TO_DAILY,dailyCoveredUntil);
        }
    }



    private void stubCheckpoint(RollupStage stage,Instant coveredUntil) {
        Optional<SensorRollupCheckpoint> result = Optional.empty();
        if (coveredUntil != null) {
            SensorRollupCheckpoint checkpoint = mock(SensorRollupCheckpoint.class);
            when(checkpoint.getCoveredUntil()).thenReturn(coveredUntil);
            result = Optional.of(checkpoint);
        }
        when(checkpointRepository.findBySensorIdAndStage(SENSOR_ID,stage)).thenReturn(result);
    }



    private void verifyNoDeletes() {
        verify(readingRepository,never()).deleteOldestEligibleRetentionBatch(anyLong(),any(),anyInt());
        verify(hourlyRepository,never()).deleteOldestEligibleRetentionBatch(anyLong(),any(),anyInt());
        verify(dailyRepository,never()).deleteOldestRetentionBatch(anyLong(),any(),anyInt());
    }

    private enum Tier {
        RAW,
        HOURLY,
        DAILY
    }
}