package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.springframework.test.util.ReflectionTestUtils.setField;



class StatisticsResponseMapperTest {

    private static final Instant START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(3600);

    private final StatisticsResponseMapper mapper = new StatisticsResponseMapper(new TemperatureUnitConverter());



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,CELSIUS,C,CELSIUS,°C",
            "TEMPERATURE,FAHRENHEIT,C,FAHRENHEIT,°F",
            "TEMPERATURE,KELVIN,C,KELVIN,K",
            "HUMIDITY,FAHRENHEIT,PERCENT,PERCENT,% RH",
            "MOTION,KELVIN,,,"
    })
    void mapsSensorIdentityTimezoneAndDisplayUnits(SensorType type,TemperatureUnit preference,
                                                   MeasurementUnit canonicalUnit,String displayUnit,String symbol) {
        Sensor sensor = new Sensor(user(),type,"Kitchen","Istanbul","Kadikoy","Window","Europe/Istanbul",CREATED_AT);
        setField(sensor,"id",41L);

        StatisticsSensorDTO result = mapper.toSensorDTO(sensor,preference);

        assertThat(result).isEqualTo(new StatisticsSensorDTO(
                41L,"Kitchen",type,"Europe/Istanbul",canonicalUnit,displayUnit,symbol));
    }



    @ParameterizedTest
    @CsvSource({
            "CELSIUS,100,10,25,30",
            "FAHRENHEIT,308,50,77,86",
            "KELVIN,1192.60,283.15,298.15,303.15"
    })
    void mapsSampleWeightedTemperatureMetricsAndAppliesOffsetsPerSample(
            TemperatureUnit unit,String sum,String minimum,String average,String maximum) {
        // One sample at 10°C and three samples at 30°C have an average of 25°C.
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(4,MeasurementUnit.C,new BigDecimal("100"),10.0,30.0);

        StatisticsPeriodMetricsDTO period = mapper.toPeriodMetricsDTO(SensorType.TEMPERATURE,aggregate,unit);
        StatisticsSeriesPointDTO point = mapper.toPointDTO(
                SensorType.TEMPERATURE,interval(StatisticsPointStatus.COMPLETE,aggregate),StatisticsDisplayGranularity.HOURLY,unit);

        assertThat(period.available()).isTrue();
        assertThat(period.sourceSampleCount()).isEqualTo(4);
        assertThat(period.motionMetrics()).isNull();
        assertNumericMetrics(period.numericMetrics(),sum,minimum,average,maximum);

        assertThat(point.sourceSampleCount()).isEqualTo(4L);
        assertThat(point.motionMetrics()).isNull();
        assertNumericMetrics(point.numericMetrics(),sum,minimum,average,maximum);
    }



    @ParameterizedTest
    @EnumSource(TemperatureUnit.class)
    void humidityMetricsDoNotChangeWithTemperaturePreference(TemperatureUnit preference) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(4,MeasurementUnit.PERCENT,new BigDecimal("180"),30.0,70.0);

        StatisticsPeriodMetricsDTO period = mapper.toPeriodMetricsDTO(SensorType.HUMIDITY,aggregate,preference);
        StatisticsSeriesPointDTO point = mapper.toPointDTO(
                SensorType.HUMIDITY,interval(StatisticsPointStatus.COMPLETE,aggregate),StatisticsDisplayGranularity.HOURLY,preference);

        assertThat(period.available()).isTrue();
        assertThat(period.sourceSampleCount()).isEqualTo(4);
        assertThat(period.motionMetrics()).isNull();
        assertNumericMetrics(period.numericMetrics(),"180","30","45","70");

        assertThat(point.sourceSampleCount()).isEqualTo(4L);
        assertThat(point.motionMetrics()).isNull();
        assertNumericMetrics(point.numericMetrics(),"180","30","45","70");
    }



    @Test
    void numericAveragesUseDecimal128ForNonTerminatingDivision() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(3,MeasurementUnit.C,BigDecimal.ONE,0.0,1.0);

        StatisticsPeriodMetricsDTO result = mapper.toPeriodMetricsDTO(SensorType.TEMPERATURE,aggregate,TemperatureUnit.CELSIUS);

        assertNumericMetrics(result.numericMetrics(),"1","0","0.3333333333333333333333333333333333","1");
    }



    @Test
    void preservesNumericSumPrecisionBeyondDoublePrecision() {
        BigDecimal sum = new BigDecimal("0.1234567890123456789012345678901234567");
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(3,MeasurementUnit.C,sum,0.0,0.1);

        StatisticsPeriodMetricsDTO result = mapper.toPeriodMetricsDTO(SensorType.TEMPERATURE,aggregate,TemperatureUnit.CELSIUS);

        assertThat(result.numericMetrics().sum()).isEqualByComparingTo("0.1234567890123456789012345678901234567");
    }



    @ParameterizedTest
    @CsvSource({
            "4,1,3,25",
            "3,1,2,33.33333333333333333333333333333333",
            "4,0,4,0",
            "4,4,0,100",
            "9223372036854775807,9223372036854775807,0,100"
    })
    void mapsMotionCountsAndPercentages(long total,long trueCount,long falseCount,String percentage) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.booleanSamples(total,trueCount);

        StatisticsPeriodMetricsDTO period = mapper.toPeriodMetricsDTO(SensorType.MOTION,aggregate,TemperatureUnit.CELSIUS);
        StatisticsSeriesPointDTO point = mapper.toPointDTO(
                SensorType.MOTION,interval(StatisticsPointStatus.COMPLETE,aggregate),StatisticsDisplayGranularity.HOURLY,TemperatureUnit.CELSIUS);

        assertThat(period.available()).isTrue();
        assertThat(period.sourceSampleCount()).isEqualTo(total);
        assertThat(period.numericMetrics()).isNull();
        assertMotionMetrics(period.motionMetrics(),total,trueCount,falseCount,percentage);

        assertThat(point.sourceSampleCount()).isEqualTo(total);
        assertThat(point.numericMetrics()).isNull();
        assertMotionMetrics(point.motionMetrics(),total,trueCount,falseCount,percentage);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void knownEmptyAggregatesRemainAvailableWithoutInventedMeasurements(SensorType type) {
        SensorSummaryAggregate aggregate = emptyAggregate(type);

        StatisticsPeriodMetricsDTO period = mapper.toPeriodMetricsDTO(type,aggregate,TemperatureUnit.FAHRENHEIT);
        StatisticsSeriesPointDTO point = mapper.toPointDTO(
                type,interval(StatisticsPointStatus.NO_SAMPLES,aggregate),StatisticsDisplayGranularity.HOURLY,TemperatureUnit.FAHRENHEIT);

        assertThat(period.available()).isTrue();
        assertThat(period.sourceSampleCount()).isZero();
        assertThat(period.numericMetrics()).isNull();

        assertThat(point.status()).isEqualTo(StatisticsPointStatus.NO_SAMPLES);
        assertThat(point.sourceSampleCount()).isEqualTo(0L);
        assertThat(point.numericMetrics()).isNull();

        if (type == SensorType.MOTION) {
            StatisticsMotionMetricsDTO emptyMotion = new StatisticsMotionMetricsDTO(0,0,0,null);
            assertThat(period.motionMetrics()).isEqualTo(emptyMotion);
            assertThat(point.motionMetrics()).isEqualTo(emptyMotion);
        } else {
            assertThat(period.motionMetrics()).isNull();
            assertThat(point.motionMetrics()).isNull();
        }
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void missingPeriodAggregateProducesUnavailableMetrics(SensorType type) {
        StatisticsPeriodMetricsDTO result = mapper.toPeriodMetricsDTO(type,null,TemperatureUnit.CELSIUS);

        assertThat(result).isEqualTo(new StatisticsPeriodMetricsDTO(false,0,null,null));
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,EXPIRED",
            "TEMPERATURE,ROLLUP_DELAY",
            "HUMIDITY,EXPIRED",
            "HUMIDITY,ROLLUP_DELAY",
            "MOTION,EXPIRED",
            "MOTION,ROLLUP_DELAY"
    })
    void unavailablePointsPreserveTheirStatusAndOmitCountsAndMetrics(SensorType type,StatisticsPointStatus status) {
        StatisticsSeriesPointDTO result = mapper.toPointDTO(
                type,interval(status,null),StatisticsDisplayGranularity.HOURLY,TemperatureUnit.CELSIUS);

        assertThat(result.granularity()).isEqualTo(StatisticsDisplayGranularity.HOURLY);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.bucketStart()).isEqualTo(START);
        assertThat(result.bucketEnd()).isEqualTo(END);
        assertThat(result.sourceReadingId()).isNull();
        assertThat(result.recordedAt()).isNull();
        assertThat(result.sourceSampleCount()).isNull();
        assertThat(result.numericMetrics()).isNull();
        assertThat(result.motionMetrics()).isNull();
        assertThat(result.finalizedAt()).isNull();
        assertThat(result.refreshedAt()).isNull();
    }



    @Test
    void rawPointsPreserveReadingIdentityAndTimestampAndAlwaysUseRawGranularity() {
        Instant recordedAt = START.plusNanos(123456000);
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("12.5"),12.5,12.5);
        RawStatisticsDataPoint source = new RawStatisticsDataPoint(71L,recordedAt,aggregate);

        StatisticsSeriesPointDTO result = mapper.toPointDTO(
                SensorType.TEMPERATURE,source,StatisticsDisplayGranularity.MONTHLY,TemperatureUnit.CELSIUS);

        assertThat(result.granularity()).isEqualTo(StatisticsDisplayGranularity.RAW);
        assertThat(result.sourceReadingId()).isEqualTo(71L);
        assertThat(result.recordedAt()).isEqualTo(recordedAt);
        assertThat(result.status()).isEqualTo(StatisticsPointStatus.COMPLETE);
        assertThat(result.sourceSampleCount()).isEqualTo(1L);
        assertNumericMetrics(result.numericMetrics(),"12.5","12.5","12.5","12.5");
        assertThat(result.motionMetrics()).isNull();
        assertThat(result.bucketStart()).isNull();
        assertThat(result.bucketEnd()).isNull();
        assertThat(result.localDateStart()).isNull();
        assertThat(result.localDateEndExclusive()).isNull();
        assertThat(result.timeZoneId()).isNull();
        assertThat(result.finalizedAt()).isNull();
        assertThat(result.refreshedAt()).isNull();
    }



    @ParameterizedTest
    @CsvSource({"DAILY,1","WEEKLY,7","MONTHLY,30"})
    void intervalPointsPreserveLocalDatesTimezoneAndRefreshMetadata(StatisticsDisplayGranularity granularity,int days) {
        LocalDate date = LocalDate.of(2026,4,1);
        ZoneId zone = ZoneId.of("Europe/Istanbul");
        Instant start = date.atStartOfDay(zone).toInstant();
        Instant end = date.plusDays(days).atStartOfDay(zone).toInstant();
        Instant finalizedAt = end.plusSeconds(60);
        Instant refreshedAt = finalizedAt.plusSeconds(120);
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,20.0);
        IntervalStatisticsDataPoint source = new IntervalStatisticsDataPoint(
                new InstantRange(start,end),date,date.plusDays(days),zone.getId(),
                StatisticsPointStatus.COMPLETE,aggregate,finalizedAt,refreshedAt);

        StatisticsSeriesPointDTO result = mapper.toPointDTO(SensorType.TEMPERATURE,source,granularity,TemperatureUnit.CELSIUS);

        assertThat(result.granularity()).isEqualTo(granularity);
        assertThat(result.sourceReadingId()).isNull();
        assertThat(result.recordedAt()).isNull();
        assertThat(result.bucketStart()).isEqualTo(start);
        assertThat(result.bucketEnd()).isEqualTo(end);
        assertThat(result.localDateStart()).isEqualTo(date);
        assertThat(result.localDateEndExclusive()).isEqualTo(date.plusDays(days));
        assertThat(result.timeZoneId()).isEqualTo("Europe/Istanbul");
        assertThat(result.status()).isEqualTo(StatisticsPointStatus.COMPLETE);
        assertThat(result.sourceSampleCount()).isEqualTo(1L);
        assertNumericMetrics(result.numericMetrics(),"20","20","20","20");
        assertThat(result.motionMetrics()).isNull();
        assertThat(result.finalizedAt()).isEqualTo(finalizedAt);
        assertThat(result.refreshedAt()).isEqualTo(refreshedAt);
    }



    @Test
    void partialPointsKeepAvailableMetricsWithoutFinalizationMetadata() {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(1,MeasurementUnit.C,new BigDecimal("20"),20.0,20.0);

        StatisticsSeriesPointDTO result = mapper.toPointDTO(
                SensorType.TEMPERATURE,interval(StatisticsPointStatus.PARTIAL,aggregate),StatisticsDisplayGranularity.HOURLY,TemperatureUnit.CELSIUS);

        assertThat(result.status()).isEqualTo(StatisticsPointStatus.PARTIAL);
        assertThat(result.sourceSampleCount()).isEqualTo(1L);
        assertNumericMetrics(result.numericMetrics(),"20","20","20","20");
        assertThat(result.localDateStart()).isNull();
        assertThat(result.localDateEndExclusive()).isNull();
        assertThat(result.timeZoneId()).isNull();
        assertThat(result.finalizedAt()).isNull();
        assertThat(result.refreshedAt()).isNull();
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void rejectsAggregatesIncompatibleWithTheSensorType(SensorType type) {
        SensorSummaryAggregate incompatible = switch (type) {
            case TEMPERATURE -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT);
            case HUMIDITY -> SensorSummaryAggregate.emptyBoolean();
            case MOTION -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        };
        IntervalStatisticsDataPoint point = interval(StatisticsPointStatus.NO_SAMPLES,incompatible);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.toPeriodMetricsDTO(type,incompatible,TemperatureUnit.CELSIUS));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> mapper.toPointDTO(type,point,StatisticsDisplayGranularity.HOURLY,TemperatureUnit.CELSIUS));
    }



    @ParameterizedTest
    @CsvSource({
            "RAW,2,0,false",
            "HOURLY,0,0,true",
            "HOURLY,2,2,true",
            "HOURLY,3,3,false",
            "DAILY,0,0,true",
            "DAILY,2,2,true",
            "DAILY,3,3,false"
    })
    void csvAvailabilityUsesSummarySourceRowCountAndAnInclusiveLimit(
            StatisticsResolution resolution,int pointCount,int expectedRowCount,boolean available) {
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        StatisticsDataPoint source = resolution == StatisticsResolution.RAW
                ? new RawStatisticsDataPoint(1L,START,SensorSummaryAggregate.numeric(1,MeasurementUnit.C,BigDecimal.TEN,10.0,10.0))
                : interval(StatisticsPointStatus.NO_SAMPLES,aggregate);
        StatisticsMaterializedSeries materialized = new StatisticsMaterializedSeries(
                resolution,RawRangeAvailability.FULL,Collections.nCopies(pointCount,source));

        StatisticsCsvExportAvailabilityDTO result = mapper.toCsvExportAvailabilityDTO(materialized,2);

        assertThat(result).isEqualTo(new StatisticsCsvExportAvailabilityDTO(available,expectedRowCount,2));
    }



    @Test
    void unavailableSummaryIntervalsStillCountAsExportRows() {
        StatisticsMaterializedSeries materialized = new StatisticsMaterializedSeries(
                StatisticsResolution.HOURLY,RawRangeAvailability.EXPIRED,
                java.util.List.of(interval(StatisticsPointStatus.EXPIRED,null),interval(StatisticsPointStatus.ROLLUP_DELAY,null)));

        StatisticsCsvExportAvailabilityDTO result = mapper.toCsvExportAvailabilityDTO(materialized,2);

        assertThat(result).isEqualTo(new StatisticsCsvExportAvailabilityDTO(true,2,2));
    }



    @Test
    void coverageMappingKeepsRetentionRepresentationAndVerificationBoundariesDistinct() {
        Instant rawFrom = START.plusSeconds(10);
        Instant rawRepresentedFrom = START.plusSeconds(20);
        Instant safeThrough = END.minusSeconds(600);
        StatisticsTierAvailability raw = new StatisticsTierAvailability(
                StatisticsResolution.RAW,new StatisticsTierRetention(rawFrom,new InstantRange(rawFrom,END)),
                Optional.of(new InstantRange(rawRepresentedFrom,END)),Optional.empty());
        StatisticsRollupProgress hourlyProgress = new StatisticsRollupProgress(
                Optional.of(new InstantRange(START.minusSeconds(3600),safeThrough)),END,Duration.ofSeconds(600));
        StatisticsTierAvailability hourly = new StatisticsTierAvailability(
                StatisticsResolution.HOURLY,new StatisticsTierRetention(START.plusSeconds(30),new InstantRange(START,END)),
                Optional.of(new InstantRange(START,safeThrough)),Optional.of(hourlyProgress));
        StatisticsRollupProgress dailyProgress = new StatisticsRollupProgress(Optional.empty(),START,Duration.ofDays(1));
        StatisticsTierAvailability daily = new StatisticsTierAvailability(
                StatisticsResolution.DAILY,new StatisticsTierRetention(START.minusSeconds(86400),new InstantRange(START.minusSeconds(86400),END)),
                Optional.empty(),Optional.of(dailyProgress));
        SensorHistory history = new SensorHistory(START.minusSeconds(7200),Optional.of(START.minusSeconds(3600)));

        StatisticsCoverageDTO result = mapper.toCoverageDTO(new StatisticsAvailabilitySnapshot(history,raw,hourly,daily));

        assertThat(result.raw()).isEqualTo(new StatisticsTierCoverageDTO(
                StatisticsResolution.RAW,rawFrom,rawRepresentedFrom,END,null));
        assertThat(result.hourly()).isEqualTo(new StatisticsTierCoverageDTO(
                StatisticsResolution.HOURLY,START,START,safeThrough,
                new StatisticsRollupProgressDTO(START.minusSeconds(3600),safeThrough,END,600,true)));
        assertThat(result.daily()).isEqualTo(new StatisticsTierCoverageDTO(
                StatisticsResolution.DAILY,START.minusSeconds(86400),null,null,
                new StatisticsRollupProgressDTO(null,null,START,86400,true)));
    }



    @Test
    void coverageMappingPreservesEmptyVerificationAndZeroLagWithoutRepresentedCoverage() {
        StatisticsTierRetention retention = new StatisticsTierRetention(START,new InstantRange(START,END));
        StatisticsRollupProgress progress = new StatisticsRollupProgress(
                Optional.of(new InstantRange(START,START)),START,Duration.ZERO);
        StatisticsTierAvailability raw = new StatisticsTierAvailability(
                StatisticsResolution.RAW,retention,Optional.empty(),Optional.empty());
        StatisticsTierAvailability hourly = new StatisticsTierAvailability(
                StatisticsResolution.HOURLY,retention,Optional.empty(),Optional.of(progress));
        StatisticsTierAvailability daily = new StatisticsTierAvailability(
                StatisticsResolution.DAILY,retention,Optional.empty(),Optional.of(progress));
        SensorHistory history = new SensorHistory(START,Optional.empty());

        StatisticsCoverageDTO result = mapper.toCoverageDTO(new StatisticsAvailabilitySnapshot(history,raw,hourly,daily));

        StatisticsRollupProgressDTO expectedProgress = new StatisticsRollupProgressDTO(START,START,START,0,false);
        assertThat(result.raw()).isEqualTo(new StatisticsTierCoverageDTO(StatisticsResolution.RAW,START,null,null,null));
        assertThat(result.hourly()).isEqualTo(new StatisticsTierCoverageDTO(StatisticsResolution.HOURLY,START,null,null,expectedProgress));
        assertThat(result.daily()).isEqualTo(new StatisticsTierCoverageDTO(StatisticsResolution.DAILY,START,null,null,expectedProgress));
    }



    private static IntervalStatisticsDataPoint interval(StatisticsPointStatus status,SensorSummaryAggregate aggregate) {
        return new IntervalStatisticsDataPoint(new InstantRange(START,END),null,null,null,status,aggregate,null,null);
    }



    private static SensorSummaryAggregate emptyAggregate(SensorType type) {
        return switch (type) {
            case TEMPERATURE -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
            case HUMIDITY -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT);
            case MOTION -> SensorSummaryAggregate.emptyBoolean();
        };
    }



    private static void assertNumericMetrics(StatisticsNumericMetricsDTO metrics,String sum,String minimum,String average,String maximum) {
        assertThat(metrics).isNotNull();
        assertThat(metrics.sum()).isEqualByComparingTo(sum);
        assertThat(metrics.minimum()).isEqualByComparingTo(minimum);
        assertThat(metrics.average()).isEqualByComparingTo(average);
        assertThat(metrics.maximum()).isEqualByComparingTo(maximum);
    }



    private static void assertMotionMetrics(StatisticsMotionMetricsDTO metrics,long total,long trueCount,long falseCount,String percentage) {
        assertThat(metrics).isNotNull();
        assertThat(metrics.totalSampleCount()).isEqualTo(total);
        assertThat(metrics.trueSampleCount()).isEqualTo(trueCount);
        assertThat(metrics.falseSampleCount()).isEqualTo(falseCount);
        assertThat(metrics.truePercentage()).isEqualByComparingTo(percentage);
    }
}