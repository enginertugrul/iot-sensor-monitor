package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;


class StatisticsQueryPolicyTest {

    private static final Duration AUTO_RAW_MAXIMUM = Duration.ofMinutes(90);
    private static final Duration MAXIMUM_RANGE = Duration.ofDays(10);



    @Test
    void preservesConfiguredValues() {
        StatisticsQueryPolicy policy = new StatisticsQueryPolicy(123,456,AUTO_RAW_MAXIMUM,MAXIMUM_RANGE);

        assertThat(policy.getChartPointBudget()).isEqualTo(123);
        assertThat(policy.getCsvExportRowLimit()).isEqualTo(456);
        assertThat(policy.getAutoRawMaximumRange()).isEqualTo(AUTO_RAW_MAXIMUM);
        assertThat(policy.getMaximumRange()).isEqualTo(MAXIMUM_RANGE);
    }



    @ParameterizedTest
    @CsvSource({"1,1","10000,10000","1,10000","10000,1"})
    void acceptsIndependentPointAndRowLimitsAtBothInclusiveBounds(int chartPoints,int exportRows) {
        StatisticsQueryPolicy policy = new StatisticsQueryPolicy(chartPoints,exportRows,AUTO_RAW_MAXIMUM,MAXIMUM_RANGE);

        assertThat(policy.getChartPointBudget()).isEqualTo(chartPoints);
        assertThat(policy.getCsvExportRowLimit()).isEqualTo(exportRows);
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0,10001,Integer.MAX_VALUE})
    void rejectsChartPointBudgetsOutsideTheAllowedBounds(int chartPoints) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryPolicy(chartPoints,2500,AUTO_RAW_MAXIMUM,MAXIMUM_RANGE))
                .withMessage("chartPointBudget must be between 1 and 10000");
    }



    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE,-1,0,10001,Integer.MAX_VALUE})
    void rejectsCsvRowLimitsOutsideTheAllowedBounds(int exportRows) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,exportRows,AUTO_RAW_MAXIMUM,MAXIMUM_RANGE))
                .withMessage("csvExportRowLimit must be between 1 and 10000");
    }



    @Test
    void requiresBothDurations() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,2500,null,MAXIMUM_RANGE))
                .withMessage("autoRawMaximumRange must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,2500,AUTO_RAW_MAXIMUM,null))
                .withMessage("maximumRange must not be null");
    }



    @ParameterizedTest
    @ValueSource(longs = {0,-1,-1000000000})
    void rejectsZeroAndNegativeDurations(long nanos) {
        Duration invalid = Duration.ofNanos(nanos);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,2500,invalid,MAXIMUM_RANGE))
                .withMessage("autoRawMaximumRange must be positive");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,2500,AUTO_RAW_MAXIMUM,invalid))
                .withMessage("maximumRange must be positive");
    }



    @ParameterizedTest
    @ValueSource(strings = {"PT0.000000001S","PT1H"})
    void acceptsEqualPositiveRawAndMaximumRanges(String durationText) {
        Duration duration = Duration.parse(durationText);

        StatisticsQueryPolicy policy = new StatisticsQueryPolicy(400,2500,duration,duration);

        assertThat(policy.getAutoRawMaximumRange()).isEqualTo(duration);
        assertThat(policy.getMaximumRange()).isEqualTo(duration);
    }



    @Test
    void rejectsAnAutoRawRangeOneNanosecondAboveTheMaximumRange() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsQueryPolicy(400,2500,MAXIMUM_RANGE.plusNanos(1),MAXIMUM_RANGE))
                .withMessage("autoRawMaximumRange must not exceed maximumRange");
    }
}