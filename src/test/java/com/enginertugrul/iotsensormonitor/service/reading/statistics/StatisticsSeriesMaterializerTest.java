package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidStatisticsQueryException;
import com.enginertugrul.iotsensormonitor.repository.DailySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.COMPLETE;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.EXPIRED;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.NO_SAMPLES;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.PARTIAL;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.ROLLUP_DELAY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.AUTO;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.DAILY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.HOURLY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.RAW;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;



@ExtendWith(MockitoExtension.class)
class StatisticsSeriesMaterializerTest {

    private static final Long SENSOR_ID = 41L;
    private static final int CHART_POINT_BUDGET = 3;
    private static final int CSV_ROW_LIMIT = 4;
    private static final Instant START = Instant.parse("2026-04-15T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-12-01T12:00:00Z");
    private static final Instant RAW_FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant HOURLY_CUTOFF = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant DAILY_CUTOFF = Instant.parse("2026-01-01T00:00:00Z");
    private static final LocalDate DATE = LocalDate.of(2026,4,15);
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private HourlySensorSummaryRepository hourlySensorSummaryRepository;

    @Mock
    private DailySensorSummaryRepository dailySensorSummaryRepository;

    @Mock
    private StatisticsIntervalResolver intervalResolver;

    private Sensor sensor;
    private StatisticsAvailabilitySnapshot availability;
    private StatisticsSeriesMaterializer materializer;


    @BeforeEach
    void setUp() {
        useSensor(SensorType.TEMPERATURE,"UTC",CREATED_AT.plusSeconds(60),RAW_FROM);
        materializer = newMaterializer(CHART_POINT_BUDGET,CSV_ROW_LIMIT);
    }



    @ParameterizedTest
    @CsvSource({
            "RAW,TEMPERATURE",
            "RAW,HUMIDITY",
            "RAW,MOTION",
            "AUTO,TEMPERATURE",
            "AUTO,HUMIDITY",
            "AUTO,MOTION"
    })
    void rawAndAutoAcceptAnExactlyFullSliceAndPreserveReadingOrder(StatisticsResolution requested,SensorType type) {
        useSensor(type,"UTC",CREATED_AT.plusSeconds(60),RAW_FROM);
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubRawSlice(window,START,rawReadings(),false);

        StatisticsMaterializedSeries result = materialize(window,requested);

        assertThat(result.resolvedResolution()).isEqualTo(RAW);
        assertThat(result.rawRangeAvailability()).isEqualTo(RawRangeAvailability.FULL);
        assertThat(result.sourcePoints()).usingRecursiveComparison().isEqualTo(List.of(
                expectedRawPoint(11L,START.plusSeconds(10),10.5,false),
                expectedRawPoint(12L,START.plusSeconds(10),20.5,true),
                expectedRawPoint(13L,START.plusSeconds(20),30.5,false)));

        verifyRawQuery(window,START);
        verifyRawAvailability(window);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "0,300,600,FULL",
            "3600,300,3600,PARTIAL",
            "0,4500,4500,FULL"
    })
    void explicitRawUsesTheLatestLowerBoundAndTheEvaluatedEnd(long retainedOffset,long firstReadingOffset,
                                                              long expectedStartOffset,RawRangeAvailability rawAvailability) {
        useSensor(SensorType.TEMPERATURE,"UTC",START.plusSeconds(firstReadingOffset),START.plusSeconds(retainedOffset));
        StatisticsQueryWindow window = new StatisticsQueryWindow(
                range(600,14400),range(600,10800),START.plusSeconds(10800),true);
        Instant queryStart = START.plusSeconds(expectedStartOffset);
        SensorReading reading = reading(21L,queryStart,20.0,false);
        stubRawAvailability(window,rawAvailability);
        stubRawSlice(window,queryStart,List.of(reading),false);

        StatisticsMaterializedSeries result = materialize(window,RAW);

        assertThat(result.resolvedResolution()).isEqualTo(RAW);
        assertThat(result.rawRangeAvailability()).isEqualTo(rawAvailability);
        assertThat(result.sourcePoints()).usingRecursiveComparison()
                .isEqualTo(List.of(expectedRawPoint(21L,queryStart,20.0,false)));
        verifyRawQuery(window,queryStart);
        verifyNoInteractions(hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "none,0,FULL",
            "3600,0,FULL",
            "7200,0,FULL",
            "-3600,3600,EXPIRED",
            "-3600,7200,EXPIRED"
    },nullValues = "none")
    void rawSkipsRepositoryQueriesWhenNoDataBearingRetainedRangeRemains(Long firstReadingOffset,
                                                                        long retainedOffset,RawRangeAvailability rawAvailability) {
        Instant firstReading = firstReadingOffset == null ? null : START.plusSeconds(firstReadingOffset);
        useSensor(SensorType.TEMPERATURE,"UTC",firstReading,START.plusSeconds(retainedOffset));
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,rawAvailability);

        StatisticsMaterializedSeries result = materialize(window,RAW);

        assertSeries(result,RAW,rawAvailability);
        verifyRawAvailability(window);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"RAW","AUTO"})
    void anEmptyRawSliceRemainsAnEmptyRawSeries(StatisticsResolution requested) {
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubRawSlice(window,START,List.of(),false);

        StatisticsMaterializedSeries result = materialize(window,requested);

        assertSeries(result,RAW,RawRangeAvailability.FULL);
        verifyRawQuery(window,START);
        verifyNoInteractions(hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @Test
    void explicitRawRejectsASecondPageWithoutFetchingItOrChangingResolution() {
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubRawSlice(window,START,rawReadings(),true);

        assertThatThrownBy(() -> materialize(window,RAW))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested RAW resolution exceeds the chart point budget of 3");

        verifyRawQuery(window,START);
        verifyNoInteractions(hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @Test
    void autoDiscardsAnOversizedRawSliceAndFallsBackToHourly() {
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubRawSlice(window,START,rawReadings(),true);
        stubHourlyRows(window);
        IntervalStatisticsDataPoint hourly = stubHourlyPoint(range(0,3600),range(0,3600),null,COMPLETE);

        StatisticsMaterializedSeries result = materialize(window,AUTO);

        assertSeries(result,HOURLY,RawRangeAvailability.FULL,hourly);
        verifyRawQuery(window,START);
        verifyHourlyQuery(window);
        verifyNoInteractions(dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "PARTIAL,3600",
            "EXPIRED,3600",
            "FULL,7200"
    })
    void autoSkipsRawWhenCoverageOrDurationMakesItIneligible(RawRangeAvailability rawAvailability,long endOffset) {
        StatisticsQueryWindow window = window(0,endOffset);
        stubRawAvailability(window,rawAvailability);
        stubHourlyRows(window);
        List<StatisticsDataPoint> expected = new ArrayList<>();
        expected.add(stubHourlyPoint(range(0,3600),range(0,3600),null,COMPLETE));

        if (endOffset == 7200) {
            expected.add(stubHourlyPoint(range(3600,7200),range(3600,7200),null,COMPLETE));
        }

        StatisticsMaterializedSeries result = materialize(window,AUTO);

        assertThat(result.resolvedResolution()).isEqualTo(HOURLY);
        assertThat(result.rawRangeAvailability()).isEqualTo(rawAvailability);
        assertThat(result.sourcePoints()).containsExactlyElementsOf(expected);
        verifyHourlyQuery(window);
        verifyNoInteractions(sensorReadingRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"COMPLETE","NO_SAMPLES","PARTIAL"})
    void autoAcceptsAvailableHourlyStatuses(StatisticsPointStatus status) {
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.EXPIRED);
        stubHourlyRows(window);
        IntervalStatisticsDataPoint point = stubHourlyPoint(range(0,3600),range(0,3600),null,status);

        StatisticsMaterializedSeries result = materialize(window,AUTO);

        assertSeries(result,HOURLY,RawRangeAvailability.EXPIRED,point);
        verifyHourlyQuery(window);
        verifyNoInteractions(sensorReadingRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"EXPIRED","ROLLUP_DELAY"})
    void autoFallsBackToDailyWhenALaterHourlyPointIsUnavailable(StatisticsPointStatus unavailableStatus) {
        StatisticsQueryWindow window = window(0,7200);
        stubRawAvailability(window,RawRangeAvailability.EXPIRED);
        stubHourlyRows(window);
        stubHourlyPoint(range(0,3600),range(0,3600),null,COMPLETE);
        stubHourlyPoint(range(3600,7200),range(3600,7200),null,unavailableStatus);
        stubDailyRows(window,UTC);
        IntervalStatisticsDataPoint daily = stubDailyPoint(range(0,7200),range(0,86400),DATE,UTC,null,unavailableStatus);

        StatisticsMaterializedSeries result = materialize(window,AUTO);

        assertSeries(result,DAILY,RawRangeAvailability.EXPIRED,daily);
        verifyHourlyQuery(window);
        verifyDailyQuery(window,UTC);
        verifyNoInteractions(sensorReadingRepository);
    }



    @Test
    void autoSkipsHourlyBeforeQueryingWhenPartialEdgesExceedThePointBudget() {
        StatisticsQueryWindow window = window(1800,12600);
        stubRawAvailability(window,RawRangeAvailability.EXPIRED);
        stubDailyRows(window,UTC);
        IntervalStatisticsDataPoint daily = stubDailyPoint(range(1800,12600),range(0,86400),DATE,UTC,null,COMPLETE);

        StatisticsMaterializedSeries result = materialize(window,AUTO);

        assertSeries(result,DAILY,RawRangeAvailability.EXPIRED,daily);
        verifyDailyQuery(window,UTC);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(StatisticsPointStatus.class)
    void explicitHourlyPreservesResolverStatusesWithoutAutomaticFallback(StatisticsPointStatus status) {
        StatisticsQueryWindow window = window(0,3600);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubHourlyRows(window);
        IntervalStatisticsDataPoint point = stubHourlyPoint(range(0,3600),range(0,3600),null,status);

        StatisticsMaterializedSeries result = materialize(window,HOURLY);

        assertSeries(result,HOURLY,RawRangeAvailability.FULL,point);
        verifyHourlyQuery(window);
        verifyNoInteractions(sensorReadingRepository,dailySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({"0,14400","1800,12600"})
    void explicitHourlyRejectsTooManyTouchedBucketsBeforeRepositoryAccess(long startOffset,long endOffset) {
        StatisticsQueryWindow window = window(startOffset,endOffset);
        stubRawAvailability(window,RawRangeAvailability.FULL);

        assertThatThrownBy(() -> materialize(window,HOURLY))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested HOURLY resolution exceeds the chart point budget of 3");

        verifyRawAvailability(window);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository,dailySensorSummaryRepository);
    }



    @Test
    void hourlyBucketsStayUtcAlignedAndUseClippedSegmentsAndMatchingSummaryRows() {
        useSensor(SensorType.TEMPERATURE,"Asia/Kathmandu",CREATED_AT.plusSeconds(60),RAW_FROM);
        StatisticsQueryWindow window = new StatisticsQueryWindow(
                range(1800,14400),range(1800,9000),START.plusSeconds(9000),true);
        HourlySensorSummary middleSummary = HourlySensorSummary.create(sensor,START.plusSeconds(3600),
                SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C),START.plusSeconds(7260));
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubHourlyRows(window,middleSummary);

        IntervalStatisticsDataPoint first = stubHourlyPoint(range(1800,3600),range(0,3600),null,COMPLETE);
        IntervalStatisticsDataPoint middle = stubHourlyPoint(range(3600,7200),range(3600,7200),middleSummary,NO_SAMPLES);
        IntervalStatisticsDataPoint last = stubHourlyPoint(range(7200,9000),range(7200,10800),null,PARTIAL);

        StatisticsMaterializedSeries result = materialize(window,HOURLY);

        assertSeries(result,HOURLY,RawRangeAvailability.FULL,first,middle,last);
        verifyHourlyQuery(window);
        verifyRawAvailability(window);
        verify(intervalResolver).resolveHourlyInterval(sensor,range(1800,3600),range(0,3600),null,availability);
        verify(intervalResolver).resolveHourlyInterval(sensor,range(3600,7200),range(3600,7200),middleSummary,availability);
        verify(intervalResolver).resolveHourlyInterval(sensor,range(7200,9000),range(7200,10800),null,availability);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(sensorReadingRepository,dailySensorSummaryRepository);
    }



    @Test
    void dailyMaterializationPreservesSourceDaysBeyondTheChartPointBudget() {
        StatisticsQueryWindow window = window(0,4 * 86400);
        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubDailyRows(window,UTC);
        List<StatisticsDataPoint> expected = new ArrayList<>();

        for (int index = 0; index < 4; index++) {
            InstantRange day = range(index * 86400L,(index + 1) * 86400L);
            expected.add(stubDailyPoint(day,day,DATE.plusDays(index),UTC,null,COMPLETE));
        }

        StatisticsMaterializedSeries result = materialize(window,DAILY);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.rawRangeAvailability()).isEqualTo(RawRangeAvailability.FULL);
        assertThat(result.sourcePoints()).containsExactlyElementsOf(expected);
        verifyDailyQuery(window,UTC);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "false,Europe/Berlin,2026-03-29,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z",
            "true,Europe/Berlin,2026-03-29,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z",
            "false,Europe/Berlin,2026-10-25,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z",
            "true,Europe/Berlin,2026-10-25,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z",
            "false,Asia/Kathmandu,2026-04-15,2026-04-14T18:15:00Z,2026-04-15T18:15:00Z",
            "true,Asia/Kathmandu,2026-04-15,2026-04-14T18:15:00Z,2026-04-15T18:15:00Z",
            "false,Pacific/Kiritimati,2026-04-15,2026-04-14T10:00:00Z,2026-04-15T10:00:00Z",
            "true,Pacific/Kiritimati,2026-04-15,2026-04-14T10:00:00Z,2026-04-15T10:00:00Z",
            "false,UTC,2026-04-15,2026-04-15T00:00:00Z,2026-04-16T00:00:00Z",
            "true,UTC,2026-04-15,2026-04-15T00:00:00Z,2026-04-16T00:00:00Z"
    })
    void dailySeriesAndExportsUseActualLocalDayBoundaries(boolean exporting,String zoneId,String dateText,
                                                          String startText,String endText) {
        useSensor(SensorType.TEMPERATURE,zoneId,CREATED_AT.plusSeconds(60),RAW_FROM);
        materializer = newMaterializer(CHART_POINT_BUDGET,1);
        ZoneId timeZone = ZoneId.of(zoneId);
        LocalDate localDate = LocalDate.parse(dateText);
        InstantRange day = new InstantRange(Instant.parse(startText),Instant.parse(endText));
        StatisticsQueryWindow window = window(day.startInclusive(),day.endExclusive());
        DailySensorSummary summary = DailySensorSummary.create(sensor,localDate,timeZone,
                aggregate(COMPLETE),day.endExclusive().plusSeconds(60));
        stubDailyRows(window,timeZone,summary);
        IntervalStatisticsDataPoint point = stubDailyPoint(day,day,localDate,timeZone,summary,COMPLETE);

        if (exporting) {
            StatisticsMaterializedExport result = export(window,DAILY);
            assertThat(result.resolvedResolution()).isEqualTo(DAILY);
            assertThat(result.rows()).containsExactly(point);
        } else {
            stubRawAvailability(window,RawRangeAvailability.FULL);
            StatisticsMaterializedSeries result = materialize(window,DAILY);
            assertSeries(result,DAILY,RawRangeAvailability.FULL,point);
            verifyRawAvailability(window);
        }

        verifyDailyQuery(window,timeZone);
        verify(intervalResolver).resolveDailyInterval(sensor,day,day,localDate,timeZone,summary,availability);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @Test
    void dailyBucketsClipBothEdgesAndMatchRowsByLocalDateAcrossDst() {
        useSensor(SensorType.TEMPERATURE,"Europe/Berlin",CREATED_AT.plusSeconds(60),RAW_FROM);
        ZoneId timeZone = ZoneId.of("Europe/Berlin");
        LocalDate firstDate = LocalDate.of(2026,3,28);
        Instant firstStart = Instant.parse("2026-03-27T23:00:00Z");
        Instant firstEnd = Instant.parse("2026-03-28T23:00:00Z");
        Instant middleEnd = Instant.parse("2026-03-29T22:00:00Z");
        Instant lastEnd = Instant.parse("2026-03-30T22:00:00Z");
        StatisticsQueryWindow window = window(
                Instant.parse("2026-03-28T05:00:00Z"),Instant.parse("2026-03-30T04:00:00Z"));

        InstantRange firstBucket = new InstantRange(firstStart,firstEnd);
        InstantRange middleBucket = new InstantRange(firstEnd,middleEnd);
        InstantRange lastBucket = new InstantRange(middleEnd,lastEnd);
        InstantRange firstSegment = new InstantRange(window.evaluated().startInclusive(),firstEnd);
        InstantRange lastSegment = new InstantRange(middleEnd,window.evaluated().endExclusive());
        DailySensorSummary firstSummary = DailySensorSummary.create(sensor,firstDate,timeZone,aggregate(COMPLETE),firstEnd.plusSeconds(60));
        DailySensorSummary lastSummary = DailySensorSummary.create(sensor,firstDate.plusDays(2),timeZone,aggregate(COMPLETE),lastEnd.plusSeconds(60));

        stubRawAvailability(window,RawRangeAvailability.FULL);
        stubDailyRows(window,timeZone,firstSummary,lastSummary);
        IntervalStatisticsDataPoint first = stubDailyPoint(firstSegment,firstBucket,firstDate,timeZone,firstSummary,COMPLETE);
        IntervalStatisticsDataPoint middle = stubDailyPoint(middleBucket,middleBucket,firstDate.plusDays(1),timeZone,null,ROLLUP_DELAY);
        IntervalStatisticsDataPoint last = stubDailyPoint(lastSegment,lastBucket,firstDate.plusDays(2),timeZone,lastSummary,COMPLETE);

        StatisticsMaterializedSeries result = materialize(window,DAILY);

        assertSeries(result,DAILY,RawRangeAvailability.FULL,first,middle,last);
        verifyDailyQuery(window,timeZone);
        verifyRawAvailability(window);
        verify(intervalResolver).resolveDailyInterval(sensor,firstSegment,firstBucket,firstDate,timeZone,firstSummary,availability);
        verify(intervalResolver).resolveDailyInterval(sensor,middleBucket,middleBucket,firstDate.plusDays(1),timeZone,null,availability);
        verify(intervalResolver).resolveDailyInterval(sensor,lastSegment,lastBucket,firstDate.plusDays(2),timeZone,lastSummary,availability);
        verifyNoMoreInteractions(intervalResolver);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @Test
    void summaryExportRejectsRawBeforeCallingCollaborators() {
        StatisticsQueryWindow window = window(0,3600);

        assertThatIllegalStateException()
                .isThrownBy(() -> export(window,RAW))
                .withMessage("RAW export must be rejected by the query service");

        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository,dailySensorSummaryRepository,intervalResolver);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"AUTO","HOURLY","DAILY"})
    void exportsExactlyTheCsvRowLimitEvenWhenItExceedsTheChartBudget(StatisticsResolution requested) {
        StatisticsResolution resolved = requested == AUTO ? HOURLY : requested;
        long bucketSeconds = resolved == HOURLY ? 3600 : 86400;
        StatisticsQueryWindow window = window(bucketSeconds / 2,3 * bucketSeconds + bucketSeconds / 2);
        List<StatisticsDataPoint> expected = new ArrayList<>();
        List<StatisticsPointStatus> statuses = List.of(COMPLETE,NO_SAMPLES,EXPIRED,ROLLUP_DELAY);

        if (resolved == HOURLY) {
            stubHourlyRows(window);
        } else {
            stubDailyRows(window,UTC);
        }

        for (int index = 0; index < CSV_ROW_LIMIT; index++) {
            InstantRange bucket = range(index * bucketSeconds,(index + 1) * bucketSeconds);
            Instant segmentStart = index == 0 ? window.evaluated().startInclusive() : bucket.startInclusive();
            Instant segmentEnd = index == CSV_ROW_LIMIT - 1 ? window.evaluated().endExclusive() : bucket.endExclusive();
            InstantRange segment = new InstantRange(segmentStart,segmentEnd);
            StatisticsPointStatus status = requested == AUTO ? COMPLETE : statuses.get(index);

            expected.add(resolved == HOURLY
                    ? stubHourlyPoint(segment,bucket,null,status)
                    : stubDailyPoint(segment,bucket,DATE.plusDays(index),UTC,null,status));
        }

        StatisticsMaterializedExport result = export(window,requested);

        assertThat(result.resolvedResolution()).isEqualTo(resolved);
        assertThat(result.rows()).containsExactlyElementsOf(expected);
        assertThat(result.rows()).hasSize(CSV_ROW_LIMIT);

        if (resolved == HOURLY) {
            verifyHourlyQuery(window);
            verifyNoInteractions(dailySensorSummaryRepository);
        } else {
            verifyDailyQuery(window,UTC);
            verifyNoInteractions(hourlySensorSummaryRepository);
        }

        verifyNoInteractions(sensorReadingRepository);
        verify(intervalResolver,never()).determineRawRangeAvailability(any(),any(),any());
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"AUTO","HOURLY","DAILY"})
    void exportsRejectTooManyTouchedRowsBeforeRepositoryAccess(StatisticsResolution requested) {
        StatisticsResolution rejected = requested == AUTO ? DAILY : requested;
        long bucketSeconds = rejected == HOURLY ? 3600 : 86400;
        StatisticsQueryWindow window = window(bucketSeconds / 2,4 * bucketSeconds + bucketSeconds / 2);

        assertThatThrownBy(() -> export(window,requested))
                .isInstanceOf(InvalidStatisticsQueryException.class)
                .hasMessage("The requested " + rejected + " CSV export would contain 5 rows, exceeding the configured limit of 4");

        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository,dailySensorSummaryRepository,intervalResolver);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"COMPLETE","NO_SAMPLES","PARTIAL"})
    void autoExportAcceptsAvailableHourlyStatusesWithoutConsideringRaw(StatisticsPointStatus status) {
        StatisticsQueryWindow window = window(0,3600);
        stubHourlyRows(window);
        IntervalStatisticsDataPoint point = stubHourlyPoint(range(0,3600),range(0,3600),null,status);

        StatisticsMaterializedExport result = export(window,AUTO);

        assertThat(result.resolvedResolution()).isEqualTo(HOURLY);
        assertThat(result.rows()).containsExactly(point);
        verifyHourlyQuery(window);
        verifyNoInteractions(sensorReadingRepository,dailySensorSummaryRepository);
        verify(intervalResolver,never()).determineRawRangeAvailability(any(),any(),any());
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsPointStatus.class,names = {"EXPIRED","ROLLUP_DELAY"})
    void autoExportFallsBackWhenAnyHourlyRowIsUnavailable(StatisticsPointStatus unavailableStatus) {
        StatisticsQueryWindow window = window(0,7200);
        stubHourlyRows(window);
        stubHourlyPoint(range(0,3600),range(0,3600),null,COMPLETE);
        stubHourlyPoint(range(3600,7200),range(3600,7200),null,unavailableStatus);
        stubDailyRows(window,UTC);
        IntervalStatisticsDataPoint daily = stubDailyPoint(range(0,7200),range(0,86400),DATE,UTC,null,unavailableStatus);

        StatisticsMaterializedExport result = export(window,AUTO);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.rows()).containsExactly(daily);
        verifyHourlyQuery(window);
        verifyDailyQuery(window,UTC);
        verifyNoInteractions(sensorReadingRepository);
        verify(intervalResolver,never()).determineRawRangeAvailability(any(),any(),any());
    }



    @Test
    void autoExportSkipsAnOversizedHourlyTierBeforeReadingItsRows() {
        StatisticsQueryWindow window = window(0,5 * 3600);
        stubDailyRows(window,UTC);
        IntervalStatisticsDataPoint daily = stubDailyPoint(range(0,5 * 3600),range(0,86400),DATE,UTC,null,COMPLETE);

        StatisticsMaterializedExport result = export(window,AUTO);

        assertThat(result.resolvedResolution()).isEqualTo(DAILY);
        assertThat(result.rows()).containsExactly(daily);
        verifyDailyQuery(window,UTC);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
        verify(intervalResolver,never()).determineRawRangeAvailability(any(),any(),any());
    }



    private StatisticsSeriesMaterializer newMaterializer(int chartPoints,int exportRows) {
        StatisticsQueryPolicy queryPolicy = new StatisticsQueryPolicy(chartPoints,exportRows,Duration.ofHours(1),Duration.ofDays(365));
        StatisticsResolutionPolicy resolutionPolicy = new StatisticsResolutionPolicy(queryPolicy);
        return new StatisticsSeriesMaterializer(sensorReadingRepository,hourlySensorSummaryRepository,
                dailySensorSummaryRepository,queryPolicy,resolutionPolicy,intervalResolver);
    }



    private void useSensor(SensorType type,String zoneId,Instant firstReadingAt,Instant rawFrom) {
        sensor = new Sensor(user(),type,"Living room","Istanbul","Kadikoy","Window",zoneId,CREATED_AT);
        setField(sensor,"id",SENSOR_ID);

        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,firstReadingAt);
        }

        InstantRange retainedRaw = new InstantRange(rawFrom,AS_OF);
        StatisticsTierAvailability raw = new StatisticsTierAvailability(RAW,
                new StatisticsTierRetention(rawFrom,retainedRaw),Optional.of(retainedRaw),Optional.empty());
        availability = new StatisticsAvailabilitySnapshot(SensorHistory.from(sensor),raw,
                summaryTier(HOURLY,HOURLY_CUTOFF),summaryTier(DAILY,DAILY_CUTOFF));
    }



    private static StatisticsTierAvailability summaryTier(StatisticsResolution resolution,Instant cutoff) {
        StatisticsTierRetention retention = new StatisticsTierRetention(cutoff,new InstantRange(cutoff,AS_OF));
        StatisticsRollupProgress progress = new StatisticsRollupProgress(Optional.empty(),AS_OF,Duration.ZERO);
        return new StatisticsTierAvailability(resolution,retention,Optional.empty(),Optional.of(progress));
    }



    private static InstantRange range(long startOffset,long endOffset) {
        return new InstantRange(START.plusSeconds(startOffset),START.plusSeconds(endOffset));
    }



    private static StatisticsQueryWindow window(long startOffset,long endOffset) {
        return window(START.plusSeconds(startOffset),START.plusSeconds(endOffset));
    }



    private static StatisticsQueryWindow window(Instant startInclusive,Instant endExclusive) {
        InstantRange range = new InstantRange(startInclusive,endExclusive);
        return new StatisticsQueryWindow(range,range,AS_OF,false);
    }



    private StatisticsMaterializedSeries materialize(StatisticsQueryWindow window,StatisticsResolution resolution) {
        return materializer.materialize(sensor,window,ZoneId.of(sensor.getTimezone()),resolution,availability);
    }



    private StatisticsMaterializedExport export(StatisticsQueryWindow window,StatisticsResolution resolution) {
        return materializer.materializeSummaryExport(sensor,window,ZoneId.of(sensor.getTimezone()),resolution,availability);
    }



    private void stubRawAvailability(StatisticsQueryWindow window,RawRangeAvailability result) {
        when(intervalResolver.determineRawRangeAvailability(window,availability.history(),availability.raw())).thenReturn(result);
    }



    private void verifyRawAvailability(StatisticsQueryWindow window) {
        verify(intervalResolver).determineRawRangeAvailability(window,availability.history(),availability.raw());
    }



    private void stubRawSlice(StatisticsQueryWindow window,Instant queryStart,List<SensorReading> readings,boolean hasNext) {
        PageRequest page = PageRequest.of(0,CHART_POINT_BUDGET);
        when(sensorReadingRepository.findForStatisticsRange(SENSOR_ID,queryStart,window.evaluated().endExclusive(),page))
                .thenReturn(new SliceImpl<>(readings,page,hasNext));
    }



    private void verifyRawQuery(StatisticsQueryWindow window,Instant queryStart) {
        verify(sensorReadingRepository).findForStatisticsRange(
                SENSOR_ID,queryStart,window.evaluated().endExclusive(),PageRequest.of(0,CHART_POINT_BUDGET));
        verifyNoMoreInteractions(sensorReadingRepository);
    }



    private void stubHourlyRows(StatisticsQueryWindow window,HourlySensorSummary... summaries) {
        when(hourlySensorSummaryRepository.findForStatisticsRange(
                SENSOR_ID,HOURLY_CUTOFF,window.evaluated().startInclusive(),window.evaluated().endExclusive()))
                .thenReturn(List.of(summaries));
    }



    private void verifyHourlyQuery(StatisticsQueryWindow window) {
        verify(hourlySensorSummaryRepository).findForStatisticsRange(
                SENSOR_ID,HOURLY_CUTOFF,window.evaluated().startInclusive(),window.evaluated().endExclusive());
        verifyNoMoreInteractions(hourlySensorSummaryRepository);
    }



    private void stubDailyRows(StatisticsQueryWindow window,ZoneId timeZone,DailySensorSummary... summaries) {
        when(dailySensorSummaryRepository.findForStatisticsRange(
                SENSOR_ID,timeZone.getId(),DAILY_CUTOFF,window.evaluated().startInclusive(),window.evaluated().endExclusive()))
                .thenReturn(List.of(summaries));
    }



    private void verifyDailyQuery(StatisticsQueryWindow window,ZoneId timeZone) {
        verify(dailySensorSummaryRepository).findForStatisticsRange(
                SENSOR_ID,timeZone.getId(),DAILY_CUTOFF,window.evaluated().startInclusive(),window.evaluated().endExclusive());
        verifyNoMoreInteractions(dailySensorSummaryRepository);
    }



    private IntervalStatisticsDataPoint stubHourlyPoint(InstantRange segment,InstantRange bucket,
                                                        HourlySensorSummary summary,StatisticsPointStatus status) {
        IntervalStatisticsDataPoint point = new IntervalStatisticsDataPoint(segment,null,null,null,status,aggregate(status),null,null);
        when(intervalResolver.resolveHourlyInterval(sensor,segment,bucket,summary,availability)).thenReturn(point);
        return point;
    }



    private IntervalStatisticsDataPoint stubDailyPoint(InstantRange segment,InstantRange bucket,LocalDate localDate,
                                                       ZoneId timeZone,DailySensorSummary summary,StatisticsPointStatus status) {
        IntervalStatisticsDataPoint point = new IntervalStatisticsDataPoint(
                segment,localDate,localDate.plusDays(1),timeZone.getId(),status,aggregate(status),null,null);
        when(intervalResolver.resolveDailyInterval(sensor,segment,bucket,localDate,timeZone,summary,availability)).thenReturn(point);
        return point;
    }



    private static SensorSummaryAggregate aggregate(StatisticsPointStatus status) {
        if (status == EXPIRED || status == ROLLUP_DELAY) {
            return null;
        }

        if (status == NO_SAMPLES) {
            return SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);
        }

        return SensorSummaryAggregate.numeric(1,MeasurementUnit.C,BigDecimal.valueOf(20.0),20.0,20.0);
    }



    private List<SensorReading> rawReadings() {
        return List.of(
                reading(11L,START.plusSeconds(10),10.5,false),
                reading(12L,START.plusSeconds(10),20.5,true),
                reading(13L,START.plusSeconds(20),30.5,false));
    }



    private SensorReading reading(Long id,Instant recordedAt,double numericValue,boolean detected) {
        SensorReading reading = switch (sensor.getType()) {
            case TEMPERATURE -> SensorReading.temperature(sensor,numericValue,recordedAt);
            case HUMIDITY -> SensorReading.humidity(sensor,numericValue,recordedAt);
            case MOTION -> SensorReading.motion(sensor,detected,recordedAt);
        };

        setField(reading,"id",id);
        return reading;
    }



    private RawStatisticsDataPoint expectedRawPoint(Long id,Instant recordedAt,double numericValue,boolean detected) {
        if (sensor.getType() == SensorType.MOTION) {
            return new RawStatisticsDataPoint(id,recordedAt,SensorSummaryAggregate.booleanSamples(1,detected ? 1 : 0));
        }

        MeasurementUnit unit = sensor.getType() == SensorType.HUMIDITY ? MeasurementUnit.PERCENT : MeasurementUnit.C;
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.numeric(
                1,unit,BigDecimal.valueOf(numericValue),numericValue,numericValue);
        return new RawStatisticsDataPoint(id,recordedAt,aggregate);
    }



    private static void assertSeries(StatisticsMaterializedSeries result,StatisticsResolution resolution,
                                     RawRangeAvailability rawAvailability,StatisticsDataPoint... points) {
        assertThat(result.resolvedResolution()).isEqualTo(resolution);
        assertThat(result.rawRangeAvailability()).isEqualTo(rawAvailability);
        assertThat(result.sourcePoints()).containsExactly(points);
    }
}