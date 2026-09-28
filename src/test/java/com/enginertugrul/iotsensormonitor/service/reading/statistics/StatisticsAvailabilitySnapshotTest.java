package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;



class StatisticsAvailabilitySnapshotTest {

    private static final Instant START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(10);
    private static final InstantRange RANGE = new InstantRange(START,END);
    private static final SensorHistory HISTORY = new SensorHistory(START,Optional.of(START));
    private static final StatisticsTierRetention RETENTION = new StatisticsTierRetention(START,RANGE);
    private static final StatisticsRollupProgress PROGRESS = new StatisticsRollupProgress(Optional.of(RANGE),END,Duration.ZERO);



    @Test
    void retentionPreservesAnExactCutoffDistinctFromTheAlignedWindowStart() {
        Instant cutoff = START.plusSeconds(5);

        StatisticsTierRetention retention = new StatisticsTierRetention(cutoff,RANGE);

        assertThat(retention.expirationCutoff()).isEqualTo(cutoff);
        assertThat(retention.retentionWindow()).isEqualTo(RANGE);
    }



    @Test
    void retentionRequiresItsCutoffAndWindow() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierRetention(null,RANGE))
                .withMessage("expirationCutoff must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierRetention(START,null))
                .withMessage("retentionWindow must not be null");
    }



    @ParameterizedTest
    @CsvSource({"0,false","1,true","3600000000000,true"})
    void rollupDelayIncludesAnyPositiveLagAtNanosecondPrecision(long lagNanos,boolean delayed) {
        Duration lag = Duration.ofNanos(lagNanos);
        StatisticsRollupProgress progress = new StatisticsRollupProgress(Optional.empty(),END,lag);

        assertThat(progress.verifiedCoverage()).isEmpty();
        assertThat(progress.rollupDueUntilExclusive()).isEqualTo(END);
        assertThat(progress.lag()).isEqualTo(lag);
        assertThat(progress.isDelayed()).isEqualTo(delayed);
    }



    @ParameterizedTest
    @ValueSource(longs = {-1,-3600000000000L})
    void rollupProgressRejectsNegativeLag(long lagNanos) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsRollupProgress(Optional.empty(),END,Duration.ofNanos(lagNanos)))
                .withMessage("lag must not be negative");
    }



    @Test
    void rollupProgressRequiresItsOptionalCoverageDueBoundaryAndLag() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsRollupProgress(null,END,Duration.ZERO))
                .withMessage("verifiedCoverage must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsRollupProgress(Optional.empty(),null,Duration.ZERO))
                .withMessage("rollupDueUntilExclusive must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsRollupProgress(Optional.empty(),END,null))
                .withMessage("lag must not be null");
    }



    @ParameterizedTest
    @CsvSource({
            "0,10,true",
            "2,8,true",
            "-1,10,false",
            "0,11,false",
            "-2,-1,false",
            "10,11,false"
    })
    void rollupVerificationRequiresCoverageOfTheEntireSourceRange(long startOffset,long endOffset,boolean expected) {
        InstantRange source = range(startOffset,endOffset);

        assertThat(PROGRESS.verifies(source)).isEqualTo(expected);
    }



    @Test
    void missingAndEmptyVerifiedCoverageCannotVerifyANonEmptySourceRange() {
        StatisticsRollupProgress missing = new StatisticsRollupProgress(Optional.empty(),END,Duration.ofSeconds(10));
        InstantRange emptyRange = new InstantRange(START,START);
        StatisticsRollupProgress initialized = new StatisticsRollupProgress(Optional.of(emptyRange),END,Duration.ofSeconds(10));

        assertThat(missing.verifiedCoverage()).isEmpty();
        assertThat(initialized.verifiedCoverage()).contains(emptyRange);
        assertThat(missing.verifies(RANGE)).isFalse();
        assertThat(initialized.verifies(RANGE)).isFalse();
    }



    @Test
    void lagDoesNotInvalidateAlreadyVerifiedCoverage() {
        StatisticsRollupProgress delayed = new StatisticsRollupProgress(Optional.of(RANGE),END.plusSeconds(10),Duration.ofSeconds(10));

        assertThat(delayed.isDelayed()).isTrue();
        assertThat(delayed.verifies(RANGE)).isTrue();
        assertThat(delayed.verifies(new InstantRange(START,END.plusNanos(1)))).isFalse();
    }



    @Test
    void rawAvailabilityHasNoRollupProgressAndDoesNotVerifySummaryCoverage() {
        StatisticsTierAvailability raw = tier(StatisticsResolution.RAW);

        assertThat(raw.representedCoverage()).contains(RANGE);
        assertThat(raw.rollupProgress()).isEmpty();
        assertThat(raw.verifies(RANGE)).isFalse();

        assertThatIllegalStateException()
                .isThrownBy(raw::requireRollupProgress)
                .withMessage("RAW has no rollup progress");
    }



    @Test
    void rawAvailabilityAcceptsAnEmptyRetentionWindowWithoutRepresentedCoverage() {
        InstantRange emptyRange = new InstantRange(START,START);
        StatisticsTierRetention retention = new StatisticsTierRetention(START.minusSeconds(3600),emptyRange);

        StatisticsTierAvailability raw = new StatisticsTierAvailability(
                StatisticsResolution.RAW,retention,Optional.empty(),Optional.empty());

        assertThat(raw.retention().retentionWindow()).isEqualTo(emptyRange);
        assertThat(raw.representedCoverage()).isEmpty();
        assertThat(raw.rollupProgress()).isEmpty();
    }



    @Test
    void rawAvailabilityRejectsRollupProgress() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsTierAvailability(StatisticsResolution.RAW,RETENTION,Optional.of(RANGE),Optional.of(PROGRESS)))
                .withMessage("Raw availability must not contain rollup progress");
    }



    @Test
    void autoCannotBeUsedAsAStorageTier() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsTierAvailability(StatisticsResolution.AUTO,RETENTION,Optional.empty(),Optional.empty()))
                .withMessage("AUTO is not a storage tier");
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void summaryAvailabilityRequiresRollupProgress(StatisticsResolution resolution) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsTierAvailability(resolution,RETENTION,Optional.empty(),Optional.empty()))
                .withMessage("Summary availability requires rollup progress");
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void summaryAvailabilityCanRepresentMissingCheckpointProgress(StatisticsResolution resolution) {
        StatisticsRollupProgress progress = new StatisticsRollupProgress(Optional.empty(),END,Duration.ofSeconds(10));
        StatisticsTierAvailability tier = new StatisticsTierAvailability(resolution,RETENTION,Optional.empty(),Optional.of(progress));

        assertThat(tier.requireRollupProgress()).isSameAs(progress);
        assertThat(tier.representedCoverage()).isEmpty();
        assertThat(tier.verifies(RANGE)).isFalse();
        assertThat(tier.requireRollupProgress().isDelayed()).isTrue();
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void summaryVerificationUsesCheckpointCoverageRatherThanRepresentedCoverage(StatisticsResolution resolution) {
        Instant retainedFrom = START.plusSeconds(5);
        InstantRange retainedRange = new InstantRange(retainedFrom,END);
        StatisticsTierRetention retention = new StatisticsTierRetention(retainedFrom,retainedRange);
        StatisticsTierAvailability tier = new StatisticsTierAvailability(resolution,retention,Optional.of(retainedRange),Optional.of(PROGRESS));

        assertThat(tier.requireRollupProgress()).isSameAs(PROGRESS);
        assertThat(tier.representedCoverage()).contains(retainedRange);
        assertThat(tier.verifies(range(0,5))).isTrue();
        assertThat(tier.verifies(RANGE)).isTrue();
        assertThat(tier.verifies(range(-1,10))).isFalse();
        assertThat(tier.verifies(range(0,11))).isFalse();
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void expiredRepresentationDoesNotEraseHistoricalVerification(StatisticsResolution resolution) {
        InstantRange laterWindow = new InstantRange(END,END.plusSeconds(10));
        StatisticsTierRetention retention = new StatisticsTierRetention(END,laterWindow);
        StatisticsTierAvailability tier = new StatisticsTierAvailability(resolution,retention,Optional.empty(),Optional.of(PROGRESS));

        assertThat(tier.representedCoverage()).isEmpty();
        assertThat(tier.verifies(RANGE)).isTrue();
        assertThat(tier.verifies(laterWindow)).isFalse();
    }



    @Test
    void tierAvailabilityRequiresEveryComponentIncludingOptionalContainers() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierAvailability(null,RETENTION,Optional.empty(),Optional.empty()))
                .withMessage("resolution must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierAvailability(StatisticsResolution.RAW,null,Optional.empty(),Optional.empty()))
                .withMessage("retention must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierAvailability(StatisticsResolution.RAW,RETENTION,null,Optional.empty()))
                .withMessage("representedCoverage must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsTierAvailability(StatisticsResolution.RAW,RETENTION,Optional.empty(),null))
                .withMessage("rollupProgress must not be null");
    }



    @Test
    void snapshotPreservesHistoryAndAllThreeAvailabilityTiers() {
        StatisticsTierAvailability raw = tier(StatisticsResolution.RAW);
        StatisticsTierAvailability hourly = tier(StatisticsResolution.HOURLY);
        StatisticsTierAvailability daily = tier(StatisticsResolution.DAILY);

        StatisticsAvailabilitySnapshot snapshot = new StatisticsAvailabilitySnapshot(HISTORY,raw,hourly,daily);

        assertThat(snapshot.history()).isSameAs(HISTORY);
        assertThat(snapshot.raw()).isSameAs(raw);
        assertThat(snapshot.hourly()).isSameAs(hourly);
        assertThat(snapshot.daily()).isSameAs(daily);
    }



    @Test
    void snapshotRequiresHistoryAndEveryTier() {
        StatisticsTierAvailability raw = tier(StatisticsResolution.RAW);
        StatisticsTierAvailability hourly = tier(StatisticsResolution.HOURLY);
        StatisticsTierAvailability daily = tier(StatisticsResolution.DAILY);

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsAvailabilitySnapshot(null,raw,hourly,daily))
                .withMessage("history must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsAvailabilitySnapshot(HISTORY,null,hourly,daily))
                .withMessage("raw must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsAvailabilitySnapshot(HISTORY,raw,null,daily))
                .withMessage("hourly must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsAvailabilitySnapshot(HISTORY,raw,hourly,null))
                .withMessage("daily must not be null");
    }



    private static InstantRange range(long startOffset,long endOffset) {
        return new InstantRange(START.plusSeconds(startOffset),START.plusSeconds(endOffset));
    }



    private static StatisticsTierAvailability tier(StatisticsResolution resolution) {
        Optional<StatisticsRollupProgress> progress = resolution == StatisticsResolution.RAW
                ? Optional.empty()
                : Optional.of(PROGRESS);

        return new StatisticsTierAvailability(resolution,RETENTION,Optional.of(RANGE),progress);
    }
}