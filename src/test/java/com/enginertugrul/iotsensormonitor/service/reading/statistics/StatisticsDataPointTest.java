package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;



class StatisticsDataPointTest {

    private static final Instant START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);
    private static final InstantRange RANGE = new InstantRange(START,END);
    private static final LocalDate DATE = LocalDate.of(2026,4,1);
    private static final SensorSummaryAggregate AGGREGATE =
            SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,20.0);



    @Test
    void rawPointPreservesItsSourceAndAlwaysReportsComplete() {
        Instant recordedAt = START.plusNanos(123456000);

        RawStatisticsDataPoint point = new RawStatisticsDataPoint(41L,recordedAt,AGGREGATE);

        assertThat(point.sourceReadingId()).isEqualTo(41L);
        assertThat(point.recordedAt()).isEqualTo(recordedAt);
        assertThat(point.aggregate()).isSameAs(AGGREGATE);
        assertThat(point.status()).isEqualTo(StatisticsPointStatus.COMPLETE);
    }



    @Test
    void rawPointRequiresItsIdentityTimestampAndAggregate() {
        assertThatNullPointerException()
                .isThrownBy(() -> new RawStatisticsDataPoint(null,START,AGGREGATE))
                .withMessage("sourceReadingId must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new RawStatisticsDataPoint(41L,null,AGGREGATE))
                .withMessage("recordedAt must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new RawStatisticsDataPoint(41L,START,null))
                .withMessage("aggregate must not be null");
    }



    @Test
    void intervalRequiresANonEmptyRangeAndAStatus() {
        assertThatNullPointerException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(null,null,null,null,StatisticsPointStatus.COMPLETE,AGGREGATE,null,null))
                .withMessage("interval must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(RANGE,null,null,null,null,AGGREGATE,null,null))
                .withMessage("status must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(
                        new InstantRange(START,START),null,null,null,StatisticsPointStatus.COMPLETE,AGGREGATE,null,null))
                .withMessage("A statistics interval must not be empty");
    }



    @ParameterizedTest
    @CsvSource({
            "true,false,false",
            "false,true,false",
            "false,false,true",
            "true,true,false",
            "true,false,true",
            "false,true,true"
    })
    void localDatesAndTimezoneMustBePopulatedTogether(boolean hasStart,boolean hasEnd,boolean hasZone) {
        LocalDate start = hasStart ? DATE : null;
        LocalDate end = hasEnd ? DATE.plusDays(1) : null;
        String zone = hasZone ? "UTC" : null;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(
                        RANGE,start,end,zone,StatisticsPointStatus.COMPLETE,AGGREGATE,null,null))
                .withMessage("Local-date and timezone fields must be populated together");
    }



    @ParameterizedTest
    @ValueSource(ints = {0,-1})
    void localDateEndMustBeAfterLocalDateStart(int endDayOffset) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(
                        RANGE,DATE,DATE.plusDays(endDayOffset),"UTC",StatisticsPointStatus.COMPLETE,AGGREGATE,null,null))
                .withMessage("localDateStart must be before localDateEndExclusive");
    }



    @Test
    void intervalPreservesCompleteLocalDateAndRefreshMetadata() {
        Instant dayEnd = START.plusSeconds(86400);
        Instant finalizedAt = dayEnd.plusSeconds(60);
        Instant refreshedAt = finalizedAt.plusSeconds(120);
        InstantRange day = new InstantRange(START,dayEnd);

        IntervalStatisticsDataPoint point = new IntervalStatisticsDataPoint(
                day,DATE,DATE.plusDays(1),"UTC",StatisticsPointStatus.COMPLETE,AGGREGATE,finalizedAt,refreshedAt);

        assertThat(point.interval()).isEqualTo(day);
        assertThat(point.localDateStart()).isEqualTo(DATE);
        assertThat(point.localDateEndExclusive()).isEqualTo(DATE.plusDays(1));
        assertThat(point.timeZoneId()).isEqualTo("UTC");
        assertThat(point.status()).isEqualTo(StatisticsPointStatus.COMPLETE);
        assertThat(point.aggregate()).isSameAs(AGGREGATE);
        assertThat(point.finalizedAt()).isEqualTo(finalizedAt);
        assertThat(point.refreshedAt()).isEqualTo(refreshedAt);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"COMPLETE","NO_SAMPLES","PARTIAL"})
    void availableStatusesRequireAnAggregate(StatisticsPointStatus status) {
        SensorSummaryAggregate aggregate = status == StatisticsPointStatus.NO_SAMPLES
                ? SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C)
                : AGGREGATE;

        IntervalStatisticsDataPoint point = interval(status,aggregate);

        assertThat(point.status()).isEqualTo(status);
        assertThat(point.aggregate()).isSameAs(aggregate);
        assertThat(point.localDateStart()).isNull();
        assertThat(point.localDateEndExclusive()).isNull();
        assertThat(point.timeZoneId()).isNull();
        assertThat(point.finalizedAt()).isNull();
        assertThat(point.refreshedAt()).isNull();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> interval(status,null))
                .withMessage("Only unavailable points may omit their aggregate");
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"EXPIRED","ROLLUP_DELAY"})
    void unavailableStatusesRequireAnAbsentAggregate(StatisticsPointStatus status) {
        IntervalStatisticsDataPoint point = interval(status,null);

        assertThat(point.status()).isEqualTo(status);
        assertThat(point.aggregate()).isNull();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> interval(status,AGGREGATE))
                .withMessage("Only unavailable points may omit their aggregate");
    }



    @ParameterizedTest
    @CsvSource({"true,false","false,true"})
    void finalizationAndRefreshTimestampsMustBePopulatedTogether(boolean hasFinalized,boolean hasRefreshed) {
        Instant finalizedAt = hasFinalized ? END : null;
        Instant refreshedAt = hasRefreshed ? END.plusSeconds(60) : null;

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntervalStatisticsDataPoint(
                        RANGE,null,null,null,StatisticsPointStatus.COMPLETE,AGGREGATE,finalizedAt,refreshedAt))
                .withMessage("finalizedAt and refreshedAt must be populated together");
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"RAW","HOURLY","DAILY"})
    void materializedSeriesAcceptsConcreteResolutionsAndEmptyPointLists(StatisticsResolution resolution) {
        StatisticsMaterializedSeries result = new StatisticsMaterializedSeries(resolution,RawRangeAvailability.FULL,List.of());

        assertThat(result.resolvedResolution()).isEqualTo(resolution);
        assertThat(result.rawRangeAvailability()).isEqualTo(RawRangeAvailability.FULL);
        assertThat(result.sourcePoints()).isEmpty();
    }



    @Test
    void materializedSeriesRequiresAConcreteResolutionAndRawAvailability() {
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedSeries(null,RawRangeAvailability.FULL,List.of()))
                .withMessage("resolvedResolution must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedSeries(StatisticsResolution.RAW,null,List.of()))
                .withMessage("rawRangeAvailability must not be null");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new StatisticsMaterializedSeries(StatisticsResolution.AUTO,RawRangeAvailability.FULL,List.of()))
                .withMessage("AUTO is not a resolved resolution");
    }



    @Test
    void materializedResultsDefensivelyCopyTheirListsAndPreserveOrder() {
        StatisticsDataPoint first = interval(StatisticsPointStatus.COMPLETE,AGGREGATE);
        StatisticsDataPoint second = new IntervalStatisticsDataPoint(
                new InstantRange(END,END.plusSeconds(3600)),null,null,null,StatisticsPointStatus.COMPLETE,AGGREGATE,null,null);
        List<StatisticsDataPoint> source = new ArrayList<>(List.of(first,second));

        StatisticsMaterializedSeries series = new StatisticsMaterializedSeries(
                StatisticsResolution.HOURLY,RawRangeAvailability.FULL,source);
        StatisticsMaterializedExport export = new StatisticsMaterializedExport(StatisticsResolution.HOURLY,source);

        source.clear();
        source.add(second);

        assertThat(series.sourcePoints()).containsExactly(first,second);
        assertThat(export.rows()).containsExactly(first,second);
        assertThatThrownBy(() -> series.sourcePoints().add(first)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> export.rows().add(first)).isInstanceOf(UnsupportedOperationException.class);
    }



    @Test
    void materializedResultsRejectNullListsAndNullElements() {
        List<StatisticsDataPoint> containingNull = new ArrayList<>();
        containingNull.add(null);

        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedSeries(StatisticsResolution.HOURLY,RawRangeAvailability.FULL,null))
                .withMessage("sourcePoints must not be null");
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedSeries(StatisticsResolution.HOURLY,RawRangeAvailability.FULL,containingNull));
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedExport(StatisticsResolution.HOURLY,null));
        assertThatNullPointerException()
                .isThrownBy(() -> new StatisticsMaterializedExport(StatisticsResolution.HOURLY,containingNull));
    }



    @Test
    void materializedExportAcceptsAnEmptyRowList() {
        StatisticsMaterializedExport result = new StatisticsMaterializedExport(StatisticsResolution.DAILY,List.of());

        assertThat(result.resolvedResolution()).isEqualTo(StatisticsResolution.DAILY);
        assertThat(result.rows()).isEmpty();
    }



    private static IntervalStatisticsDataPoint interval(StatisticsPointStatus status,SensorSummaryAggregate aggregate) {
        return new IntervalStatisticsDataPoint(RANGE,null,null,null,status,aggregate,null,null);
    }
}