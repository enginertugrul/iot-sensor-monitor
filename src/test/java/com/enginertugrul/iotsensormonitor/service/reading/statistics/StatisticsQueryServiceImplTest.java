package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.*;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.*;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.*;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;



@ExtendWith(MockitoExtension.class)
class StatisticsQueryServiceImplTest {

    private static final Long SENSOR_ID = 41L;
    private static final Long OWNER_ID = 7L;
    private static final Instant START = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-06-01T12:00:00Z");
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    @Mock
    private SensorRepository sensorRepository;

    @Mock
    private StatisticsAvailabilityResolver availabilityResolver;

    @Mock
    private StatisticsSeriesMaterializer materializer;

    private final StatisticsQueryPolicy queryPolicy =
            new StatisticsQueryPolicy(3,50,Duration.ofHours(1),Duration.ofDays(1095));

    private Sensor sensor;
    private StatisticsQueryServiceImpl service;


    @BeforeEach
    void setUp() {
        useSensor(SensorType.TEMPERATURE);
        StatisticsResponseMapper mapper = new StatisticsResponseMapper(new TemperatureUnitConverter());
        service = new StatisticsQueryServiceImpl(
                sensorRepository,availabilityResolver,materializer,queryPolicy,
                new StatisticsResolutionPolicy(queryPolicy),mapper,Clock.fixed(AS_OF,ZoneOffset.UTC));
    }



    @Test
    void normalizesMissingResolutionAndTemperaturePreferenceBeforeMaterialization() {
        Instant end = START.plusSeconds(3600);
        List<StatisticsDataPoint> points = List.of(hour(0,COMPLETE,numeric(1,"20",20.0,20.0)));
        stubSeries(START,end,null,HOURLY,RawRangeAvailability.FULL,points);

        SensorStatisticsSeriesDTO result = service.getSeries(SENSOR_ID,OWNER_ID,START,end,null,null);

        assertThat(result.sensor().id()).isEqualTo(SENSOR_ID);
        assertThat(result.sensor().timeZoneId()).isEqualTo("Europe/Istanbul");
        assertThat(result.sensor().displayUnit()).isEqualTo("CELSIUS");
        assertThat(result.requestedResolution()).isEqualTo(AUTO);
        assertThat(result.resolvedResolution()).isEqualTo(HOURLY);
        assertThat(result.displayGranularity()).isEqualTo(StatisticsDisplayGranularity.HOURLY);
        assertThat(result.requestedStartInclusive()).isEqualTo(START);
        assertThat(result.requestedEndExclusive()).isEqualTo(end);
        assertThat(result.evaluatedStartInclusive()).isEqualTo(START);
        assertThat(result.evaluatedEndExclusive()).isEqualTo(end);
        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.pointBudget()).isEqualTo(3);
        assertThat(result.periodMetrics().numericMetrics().average()).isEqualByComparingTo("20");
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
        assertThat(result.fullyCovered()).isTrue();

        verify(sensorRepository).findByIdAndOwnerId(SENSOR_ID,OWNER_ID);
        verifyNoMoreInteractions(sensorRepository);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsMissingAndForeignSensorsBeforeValidatingTheRequestedRange(boolean export) {
        for (Long sensorId : List.of(-1L,SENSOR_ID)) {
            when(sensorRepository.findByIdAndOwnerId(sensorId,OWNER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> request(export,sensorId,null,null))
                    .isExactlyInstanceOf(SensorNotFoundException.class)
                    .hasMessage("Sensor not found")
                    .hasNoCause();

            verify(sensorRepository).findByIdAndOwnerId(sensorId,OWNER_ID);
        }

        verifyNoMoreInteractions(sensorRepository);
        verifyNoInteractions(availabilityResolver,materializer);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void rejectsAnInvalidOwnedRangeBeforeResolvingAvailability(boolean export) {
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));

        assertThatThrownBy(() -> request(export,SENSOR_ID,START,START))
                .isExactlyInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("startInclusive must be before endExclusive");

        verifyNoInteractions(availabilityResolver,materializer);
    }



    @ParameterizedTest
    @CsvSource({
            "COMPLETE,COMPLETE,false,false,false,true,1",
            "NO_SAMPLES,NO_SAMPLES,false,false,false,true,0",
            "PARTIAL,PARTIAL,false,false,true,true,1",
            "ROLLUP_DELAY,ROLLUP_DELAY,false,true,false,false,0",
            "EXPIRED,EXPIRED,true,false,false,false,0",
            "EXPIRED|COMPLETE,PARTIALLY_EXPIRED,true,false,false,false,0",
            "EXPIRED|ROLLUP_DELAY,PARTIALLY_EXPIRED,true,true,false,false,0",
            "EXPIRED|ROLLUP_DELAY|PARTIAL,PARTIALLY_EXPIRED,true,true,true,false,0",
            "ROLLUP_DELAY|PARTIAL,ROLLUP_DELAY,false,true,true,false,0",
            "NO_SAMPLES|PARTIAL,PARTIAL,false,false,true,true,1"
    })
    void derivesSummaryRangeStatusUsingTheRequiredPrecedence(
            String sequence,StatisticsRangeStatus expectedStatus,boolean expired,boolean delayed,
            boolean incomplete,boolean metricsAvailable,long sampleCount) {
        String[] names = sequence.split("\\|");
        List<StatisticsDataPoint> points = new ArrayList<>();

        for (int index = 0; index < names.length; index++) {
            StatisticsPointStatus status = StatisticsPointStatus.valueOf(names[index]);
            points.add(hour(index,status,aggregateFor(status)));
        }

        SensorStatisticsSeriesDTO result = query(HOURLY,RawRangeAvailability.FULL,points);

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(expired,delayed,incomplete));
        assertThat(result.fullyCovered()).isEqualTo(!expired && !delayed && !incomplete);
        assertThat(result.periodMetrics().available()).isEqualTo(metricsAvailable);
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(sampleCount);
        assertThat(result.points()).extracting(StatisticsSeriesPointDTO::status)
                .containsExactlyElementsOf(points.stream().map(StatisticsDataPoint::status).toList());

        if (!metricsAvailable || sampleCount == 0) {
            assertThat(result.periodMetrics().numericMetrics()).isNull();
            assertThat(result.periodMetrics().motionMetrics()).isNull();
        } else {
            assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("10");
        }
    }



    @ParameterizedTest
    @CsvSource({
            "FULL,0,NO_SAMPLES,false,true,0",
            "FULL,1,COMPLETE,false,true,1",
            "PARTIAL,0,PARTIALLY_EXPIRED,true,false,0",
            "PARTIAL,1,PARTIALLY_EXPIRED,true,false,0",
            "EXPIRED,0,EXPIRED,true,false,0"
    })
    void rawRangeStatusUsesRetentionAvailabilityEvenWhenNoRetainedReadingsExist(
            RawRangeAvailability availability,int readingCount,StatisticsRangeStatus expectedStatus,
            boolean expired,boolean metricsAvailable,long sampleCount) {
        List<StatisticsDataPoint> points = readingCount == 0
                ? List.of()
                : List.of(new RawStatisticsDataPoint(91L,START,numeric(1,"20",20.0,20.0)));

        SensorStatisticsSeriesDTO result = query(RAW,availability,points);

        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(expired,false,false));
        assertThat(result.fullyCovered()).isEqualTo(!expired);
        assertThat(result.periodMetrics().available()).isEqualTo(metricsAvailable);
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(sampleCount);
        assertThat(result.points()).hasSize(readingCount);

        if (!metricsAvailable) {
            assertThat(result.periodMetrics()).isEqualTo(new StatisticsPeriodMetricsDTO(false,0,null,null));
        }
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void anEmptyCoveredSeriesHasAvailableZeroSamplePeriodMetrics(SensorType type) {
        useSensor(type);

        SensorStatisticsSeriesDTO result = query(RAW,RawRangeAvailability.FULL,List.of());

        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.NO_SAMPLES);
        assertThat(result.fullyCovered()).isTrue();
        assertThat(result.periodMetrics().available()).isTrue();
        assertThat(result.periodMetrics().sourceSampleCount()).isZero();
        assertThat(result.periodMetrics().numericMetrics()).isNull();

        if (type == SensorType.MOTION) {
            assertThat(result.periodMetrics().motionMetrics()).isEqualTo(new StatisticsMotionMetricsDTO(0,0,0,null));
        } else {
            assertThat(result.periodMetrics().motionMetrics()).isNull();
        }
    }



    @ParameterizedTest
    @CsvSource({
            "CELSIUS,100,25",
            "FAHRENHEIT,308,77",
            "KELVIN,1192.60,298.15"
    })
    void periodMetricsWeightSourceSamplesAndIgnoreEmptyBuckets(TemperatureUnit unit,String sum,String average) {
        List<StatisticsDataPoint> points = List.of(
                hour(0,COMPLETE,numeric(1,"10",10.0,10.0)),
                hour(1,COMPLETE,numeric(3,"90",30.0,30.0)),
                hour(2,NO_SAMPLES,SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C)));

        SensorStatisticsSeriesDTO result = query(
                START,START.plusSeconds(10800),HOURLY,RawRangeAvailability.FULL,points,unit);

        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
        assertThat(result.periodMetrics().available()).isTrue();
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(4);
        assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo(sum);
        assertThat(result.periodMetrics().numericMetrics().average()).isEqualByComparingTo(average);
        assertThat(result.periodMetrics().motionMetrics()).isNull();
    }



    @Test
    void motionPeriodPercentageUsesCombinedCountsRatherThanAveragingBucketPercentages() {
        useSensor(SensorType.MOTION);
        List<StatisticsDataPoint> points = List.of(
                hour(0,COMPLETE,SensorSummaryAggregate.booleanSamples(1,1)),
                hour(1,COMPLETE,SensorSummaryAggregate.booleanSamples(3,0)));

        SensorStatisticsSeriesDTO result = query(HOURLY,RawRangeAvailability.FULL,points);

        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(4);
        assertThat(result.periodMetrics().numericMetrics()).isNull();
        assertThat(result.periodMetrics().motionMetrics()).isEqualTo(
                new StatisticsMotionMetricsDTO(4,1,3,new BigDecimal("25")));
    }



    @Test
    void clippingAFutureEndMakesEvenAnEmptyRangePartial() {
        Instant start = AS_OF.minusSeconds(3600);
        Instant requestedEnd = AS_OF.plusSeconds(3600);

        SensorStatisticsSeriesDTO result = query(
                start,requestedEnd,RAW,RawRangeAvailability.FULL,List.of(),TemperatureUnit.CELSIUS);

        assertThat(result.requestedEndExclusive()).isEqualTo(requestedEnd);
        assertThat(result.evaluatedEndExclusive()).isEqualTo(AS_OF);
        assertThat(result.asOf()).isEqualTo(AS_OF);
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.PARTIAL);
        assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(false,false,true));
        assertThat(result.fullyCovered()).isFalse();
        assertThat(result.periodMetrics()).isEqualTo(new StatisticsPeriodMetricsDTO(true,0,null,null));
    }



    @Test
    void weeklyGroupingUsesMondayBoundariesWeightedMetricsAndIndependentMetadataMaxima() {
        LocalDate first = LocalDate.of(2026,3,29);
        List<StatisticsDataPoint> points = List.of(
                day(first,COMPLETE,numeric(1,"10",10.0,10.0)),
                day(first.plusDays(1),COMPLETE,numeric(3,"90",30.0,30.0),AS_OF.minusSeconds(300),AS_OF.minusSeconds(30)),
                day(first.plusDays(2),NO_SAMPLES,SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C),
                        AS_OF.minusSeconds(400),AS_OF.minusSeconds(20)),
                day(first.plusDays(3),COMPLETE,numeric(2,"40",20.0,20.0),AS_OF.minusSeconds(500),AS_OF.minusSeconds(10)));

        SensorStatisticsSeriesDTO result = query(
                midnight(first),midnight(first.plusDays(4)),DAILY,RawRangeAvailability.FULL,points,TemperatureUnit.CELSIUS);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.displayGranularity()).isEqualTo(StatisticsDisplayGranularity.WEEKLY);
        assertThat(result.points()).hasSize(2);
        assertGroupedPoint(result.points().get(0),first,first.plusDays(1),1,"10","10");
        assertGroupedPoint(result.points().get(1),first.plusDays(1),first.plusDays(4),5,"130","26");
        assertThat(result.points().get(1).finalizedAt()).isEqualTo(AS_OF.minusSeconds(300));
        assertThat(result.points().get(1).refreshedAt()).isEqualTo(AS_OF.minusSeconds(10));
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(6);
        assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("140");
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
        assertThat(result.fullyCovered()).isTrue();
    }



    @Test
    void monthlyGroupingPreservesCalendarBoundariesAndCountsEverySourceSampleOnce() {
        LocalDate first = LocalDate.of(2026,3,1);
        LocalDate end = LocalDate.of(2026,4,10);
        List<StatisticsDataPoint> points = new ArrayList<>();

        for (LocalDate date = first; date.isBefore(end); date = date.plusDays(1)) {
            SensorSummaryAggregate aggregate;

            if (date.equals(first)) {
                aggregate = numeric(1,"10",10.0,10.0);
            } else if (date.equals(LocalDate.of(2026,3,31))) {
                aggregate = numeric(3,"90",30.0,30.0);
            } else if (date.equals(LocalDate.of(2026,4,1))) {
                aggregate = numeric(1,"20",20.0,20.0);
            } else {
                aggregate = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
            }

            points.add(day(date,aggregate.getSourceSampleCount() == 0 ? NO_SAMPLES : COMPLETE,aggregate));
        }

        SensorStatisticsSeriesDTO result = query(
                midnight(first),midnight(end),DAILY,RawRangeAvailability.FULL,points,TemperatureUnit.CELSIUS);

        assertThat(result.displayGranularity()).isEqualTo(StatisticsDisplayGranularity.MONTHLY);
        assertThat(result.points()).hasSize(2);
        assertGroupedPoint(result.points().get(0),first,LocalDate.of(2026,4,1),4,"100","25");
        assertGroupedPoint(result.points().get(1),LocalDate.of(2026,4,1),end,1,"20","20");
        assertThat(result.periodMetrics().sourceSampleCount()).isEqualTo(5);
        assertThat(result.periodMetrics().numericMetrics().sum()).isEqualByComparingTo("120");
        assertThat(result.periodMetrics().numericMetrics().average()).isEqualByComparingTo("24");
        assertThat(result.status()).isEqualTo(StatisticsRangeStatus.COMPLETE);
    }



    @ParameterizedTest
    @CsvSource({
            "EXPIRED,EXPIRED,EXPIRED,EXPIRED,true,false,false,false",
            "EXPIRED,ROLLUP_DELAY,EXPIRED,PARTIALLY_EXPIRED,true,true,false,false",
            "ROLLUP_DELAY,ROLLUP_DELAY,ROLLUP_DELAY,ROLLUP_DELAY,false,true,false,false",
            "COMPLETE,EXPIRED,PARTIAL,PARTIALLY_EXPIRED,true,false,false,false",
            "COMPLETE,ROLLUP_DELAY,PARTIAL,ROLLUP_DELAY,false,true,false,false",
            "COMPLETE,PARTIAL,PARTIAL,PARTIAL,false,false,true,true",
            "NO_SAMPLES,NO_SAMPLES,NO_SAMPLES,NO_SAMPLES,false,false,false,true"
    })
    void groupedPointStatusDoesNotReplaceSourceRangeConditions(
            StatisticsPointStatus firstStatus,StatisticsPointStatus remainingStatus,
            StatisticsPointStatus expectedPointStatus,StatisticsRangeStatus expectedRangeStatus,
            boolean expired,boolean delayed,boolean incomplete,boolean metricsAvailable) {
        LocalDate first = LocalDate.of(2026,3,30);
        List<StatisticsDataPoint> points = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            StatisticsPointStatus status = index == 0 ? firstStatus : remainingStatus;
            points.add(day(first.plusDays(index),status,aggregateFor(status)));
        }

        SensorStatisticsSeriesDTO result = query(
                midnight(first),midnight(first.plusDays(4)),DAILY,RawRangeAvailability.FULL,points,TemperatureUnit.CELSIUS);

        assertThat(result.displayGranularity()).isEqualTo(StatisticsDisplayGranularity.WEEKLY);
        assertThat(result.points()).hasSize(1);
        assertThat(result.points().getFirst().status()).isEqualTo(expectedPointStatus);
        assertThat(result.status()).isEqualTo(expectedRangeStatus);
        assertThat(result.conditions()).isEqualTo(new StatisticsRangeConditionsDTO(expired,delayed,incomplete));
        assertThat(result.fullyCovered()).isEqualTo(!expired && !delayed && !incomplete);
        assertThat(result.periodMetrics().available()).isEqualTo(metricsAvailable);

        if (!metricsAvailable) {
            assertThat(result.periodMetrics()).isEqualTo(new StatisticsPeriodMetricsDTO(false,0,null,null));
        }

        if (expectedPointStatus == PARTIAL || expectedPointStatus == EXPIRED || expectedPointStatus == ROLLUP_DELAY) {
            assertThat(result.points().getFirst().finalizedAt()).isNull();
            assertThat(result.points().getFirst().refreshedAt()).isNull();
        }

        if (expectedPointStatus == EXPIRED || expectedPointStatus == ROLLUP_DELAY) {
            assertThat(result.points().getFirst().sourceSampleCount()).isNull();
            assertThat(result.points().getFirst().numericMetrics()).isNull();
        }
    }



    @Test
    void aCompleteGroupOmitsRefreshMetadataWhenAnySourcePointHasNone() {
        LocalDate first = LocalDate.of(2026,3,30);
        List<StatisticsDataPoint> points = List.of(
                day(first,COMPLETE,numeric(1,"10",10.0,10.0)),
                day(first.plusDays(1),COMPLETE,numeric(1,"10",10.0,10.0)),
                day(first.plusDays(2),COMPLETE,numeric(1,"10",10.0,10.0)),
                day(first.plusDays(3),NO_SAMPLES,SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C),null,null));

        SensorStatisticsSeriesDTO result = query(
                midnight(first),midnight(first.plusDays(4)),DAILY,RawRangeAvailability.FULL,points,TemperatureUnit.CELSIUS);

        assertThat(result.points()).hasSize(1);
        assertThat(result.points().getFirst().status()).isEqualTo(COMPLETE);
        assertThat(result.points().getFirst().sourceSampleCount()).isEqualTo(3L);
        assertThat(result.points().getFirst().finalizedAt()).isNull();
        assertThat(result.points().getFirst().refreshedAt()).isNull();
        assertThat(result.fullyCovered()).isTrue();
    }



    @Test
    void dailyMotionGroupingCombinesCountsBeforeCalculatingThePercentage() {
        useSensor(SensorType.MOTION);
        LocalDate first = LocalDate.of(2026,3,30);
        List<StatisticsDataPoint> points = List.of(
                day(first,COMPLETE,SensorSummaryAggregate.booleanSamples(1,1)),
                day(first.plusDays(1),COMPLETE,SensorSummaryAggregate.booleanSamples(3,0)),
                day(first.plusDays(2),NO_SAMPLES,SensorSummaryAggregate.emptyBoolean()),
                day(first.plusDays(3),NO_SAMPLES,SensorSummaryAggregate.emptyBoolean()));

        SensorStatisticsSeriesDTO result = query(
                midnight(first),midnight(first.plusDays(4)),DAILY,RawRangeAvailability.FULL,points,TemperatureUnit.CELSIUS);

        StatisticsMotionMetricsDTO expected = new StatisticsMotionMetricsDTO(4,1,3,new BigDecimal("25"));
        assertThat(result.points()).hasSize(1);
        assertThat(result.points().getFirst().sourceSampleCount()).isEqualTo(4L);
        assertThat(result.points().getFirst().numericMetrics()).isNull();
        assertThat(result.points().getFirst().motionMetrics()).isEqualTo(expected);
        assertThat(result.periodMetrics().motionMetrics()).isEqualTo(expected);
    }



    @Test
    void rejectsAMaterializedSeriesThatStillExceedsTheDisplayBudget() {
        List<StatisticsDataPoint> points = List.of(
                hour(0,COMPLETE,aggregateFor(COMPLETE)),
                hour(1,COMPLETE,aggregateFor(COMPLETE)),
                hour(2,COMPLETE,aggregateFor(COMPLETE)),
                hour(3,COMPLETE,aggregateFor(COMPLETE)));

        assertThatIllegalStateException()
                .isThrownBy(() -> query(HOURLY,RawRangeAvailability.FULL,points))
                .withMessage("Resolved statistics series exceeds the configured point budget");
    }



    @Test
    void summaryExportReturnsUngroupedDailyRowsBeyondTheChartBudget() {
        LocalDate first = LocalDate.of(2026,3,30);
        Instant start = midnight(first);
        Instant end = midnight(first.plusDays(4));
        List<StatisticsDataPoint> points = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            points.add(day(first.plusDays(index),COMPLETE,numeric(1,"10",10.0,10.0)));
        }

        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(start,end,AS_OF,queryPolicy.getMaximumRange());
        StatisticsAvailabilitySnapshot availability = availability();
        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));
        when(availabilityResolver.resolve(sensor,AS_OF)).thenReturn(availability);
        when(materializer.materializeSummaryExport(sensor,window,ZONE,AUTO,availability))
                .thenReturn(new StatisticsMaterializedExport(DAILY,points));

        SensorStatisticsExportDTO result = service.getSummaryExport(SENSOR_ID,OWNER_ID,start,end,null,null);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.evaluatedStartInclusive()).isEqualTo(start);
        assertThat(result.evaluatedEndExclusive()).isEqualTo(end);
        assertThat(result.sensor().displayUnit()).isEqualTo("CELSIUS");
        assertThat(result.rows()).hasSize(4)
                .allSatisfy(row -> assertThat(row.granularity()).isEqualTo(StatisticsDisplayGranularity.DAILY));
        assertThat(result.rows()).extracting(StatisticsSeriesPointDTO::localDateStart)
                .containsExactly(first,first.plusDays(1),first.plusDays(2),first.plusDays(3));

        verify(materializer).materializeSummaryExport(sensor,window,ZONE,AUTO,availability);
        verifyNoMoreInteractions(materializer);
    }



    private void useSensor(SensorType type) {
        sensor = new Sensor(user(),type,"Statistics","Istanbul","Kadikoy","Window",ZONE.getId(),CREATED_AT);
        sensor.recordFirstReading(CREATED_AT,CREATED_AT);
        setField(sensor,"id",SENSOR_ID);
    }



    private void request(boolean export,Long sensorId,Instant start,Instant end) {
        if (export) {
            service.getSummaryExport(sensorId,OWNER_ID,start,end,HOURLY,TemperatureUnit.CELSIUS);
        } else {
            service.getSeries(sensorId,OWNER_ID,start,end,HOURLY,TemperatureUnit.CELSIUS);
        }
    }



    private SensorStatisticsSeriesDTO query(StatisticsResolution resolution,RawRangeAvailability raw,List<StatisticsDataPoint> points) {
        Instant end = START.plusSeconds(3600L * Math.max(1,points.size()));
        return query(START,end,resolution,raw,points,TemperatureUnit.CELSIUS);
    }



    private SensorStatisticsSeriesDTO query(Instant start,Instant end,StatisticsResolution resolution,
                                            RawRangeAvailability raw,List<StatisticsDataPoint> points,TemperatureUnit unit) {
        stubSeries(start,end,resolution,resolution,raw,points);
        return service.getSeries(SENSOR_ID,OWNER_ID,start,end,resolution,unit);
    }



    private void stubSeries(Instant start,Instant end,StatisticsResolution requested,StatisticsResolution resolved,
                            RawRangeAvailability raw,List<StatisticsDataPoint> points) {
        StatisticsQueryWindow window = StatisticsQueryWindow.resolve(start,end,AS_OF,queryPolicy.getMaximumRange());
        StatisticsAvailabilitySnapshot availability = availability();
        StatisticsResolution effectiveResolution = requested == null ? AUTO : requested;

        when(sensorRepository.findByIdAndOwnerId(SENSOR_ID,OWNER_ID)).thenReturn(Optional.of(sensor));
        when(availabilityResolver.resolve(sensor,AS_OF)).thenReturn(availability);
        when(materializer.materialize(sensor,window,ZONE,effectiveResolution,availability))
                .thenReturn(new StatisticsMaterializedSeries(resolved,raw,points));
    }



    private StatisticsAvailabilitySnapshot availability() {
        InstantRange range = new InstantRange(CREATED_AT,AS_OF);
        StatisticsTierRetention retention = new StatisticsTierRetention(CREATED_AT,range);
        StatisticsRollupProgress progress = new StatisticsRollupProgress(Optional.of(range),AS_OF,Duration.ZERO);

        return new StatisticsAvailabilitySnapshot(
                SensorHistory.from(sensor),
                new StatisticsTierAvailability(RAW,retention,Optional.of(range),Optional.empty()),
                new StatisticsTierAvailability(HOURLY,retention,Optional.of(range),Optional.of(progress)),
                new StatisticsTierAvailability(DAILY,retention,Optional.of(range),Optional.of(progress)));
    }



    private static SensorSummaryAggregate numeric(long count,String sum,double minimum,double maximum) {
        return SensorSummaryAggregate.numeric(count,MeasurementUnit.C,new BigDecimal(sum),minimum,maximum);
    }



    private static SensorSummaryAggregate aggregateFor(StatisticsPointStatus status) {
        return switch (status) {
            case COMPLETE,PARTIAL -> numeric(1,"10",10.0,10.0);
            case NO_SAMPLES -> SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
            case EXPIRED,ROLLUP_DELAY -> null;
        };
    }



    private static IntervalStatisticsDataPoint hour(int offset,StatisticsPointStatus status,SensorSummaryAggregate aggregate) {
        Instant start = START.plusSeconds(offset * 3600L);
        return new IntervalStatisticsDataPoint(new InstantRange(start,start.plusSeconds(3600)),null,null,null,status,aggregate,null,null);
    }



    private static IntervalStatisticsDataPoint day(LocalDate date,StatisticsPointStatus status,SensorSummaryAggregate aggregate) {
        Instant finalizedAt = status == COMPLETE || status == NO_SAMPLES ? midnight(date.plusDays(1)).plusSeconds(60) : null;
        return day(date,status,aggregate,finalizedAt,finalizedAt);
    }



    private static IntervalStatisticsDataPoint day(LocalDate date,StatisticsPointStatus status,SensorSummaryAggregate aggregate,
                                                   Instant finalizedAt,Instant refreshedAt) {
        return new IntervalStatisticsDataPoint(
                new InstantRange(midnight(date),midnight(date.plusDays(1))),
                date,date.plusDays(1),ZONE.getId(),status,aggregate,finalizedAt,refreshedAt);
    }



    private static Instant midnight(LocalDate date) {
        return date.atStartOfDay(ZONE).toInstant();
    }



    private static void assertGroupedPoint(StatisticsSeriesPointDTO point,LocalDate start,LocalDate end,
                                           long samples,String sum,String average) {
        assertThat(point.bucketStart()).isEqualTo(midnight(start));
        assertThat(point.bucketEnd()).isEqualTo(midnight(end));
        assertThat(point.localDateStart()).isEqualTo(start);
        assertThat(point.localDateEndExclusive()).isEqualTo(end);
        assertThat(point.timeZoneId()).isEqualTo(ZONE.getId());
        assertThat(point.status()).isEqualTo(COMPLETE);
        assertThat(point.sourceSampleCount()).isEqualTo(samples);
        assertThat(point.numericMetrics().sum()).isEqualByComparingTo(sum);
        assertThat(point.numericMetrics().average()).isEqualByComparingTo(average);
    }
}