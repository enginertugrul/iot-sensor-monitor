package com.enginertugrul.iotsensormonitor.service.reading.statistics;

import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus;
import com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution;
import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummary;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorSummaryAggregate;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.repository.HourlySensorSummaryRepository;
import com.enginertugrul.iotsensormonitor.repository.RawSensorReadingAggregateProjection;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.COMPLETE;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.EXPIRED;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.NO_SAMPLES;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.PARTIAL;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsPointStatus.ROLLUP_DELAY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.DAILY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.HOURLY;
import static com.enginertugrul.iotsensormonitor.dto.statistics.StatisticsResolution.RAW;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;



@ExtendWith(MockitoExtension.class)
class StatisticsIntervalResolverTest {

    private static final Long SENSOR_ID = 41L;
    private static final Instant CREATED_AT = Instant.parse("2026-04-01T00:00:00Z");
    private static final Instant START = Instant.parse("2026-04-15T00:00:00Z");
    private static final Instant AS_OF = Instant.parse("2026-04-18T00:00:00Z");
    private static final Instant HOURLY_CUTOFF = Instant.parse("2026-04-10T00:00:00Z");
    private static final Instant DAILY_CUTOFF = Instant.parse("2026-04-05T00:00:00Z");
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Mock
    private SensorReadingRepository sensorReadingRepository;

    @Mock
    private HourlySensorSummaryRepository hourlySensorSummaryRepository;

    private StatisticsIntervalResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StatisticsIntervalResolver(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource(value = {
            "none,-7200,-3600,FULL",
            "0,-7200,0,FULL",
            "3600,-3600,3600,FULL",
            "-86400,-7200,-1,EXPIRED",
            "-86400,-7200,0,EXPIRED",
            "-86400,-3600,3600,PARTIAL",
            "-86400,0,3600,FULL",
            "-86400,3600,7200,FULL",
            "0,-3600,3600,FULL",
            "1800,-3600,3600,FULL"
    },nullValues = "none")
    void classifiesRawAvailabilityUsingOnlyTheDataBearingRange(Long firstReadingOffset,long startOffset,long endOffset,RawRangeAvailability expected) {
        Optional<Instant> firstReading = Optional.ofNullable(firstReadingOffset).map(START::plusSeconds);
        SensorHistory history = new SensorHistory(CREATED_AT,firstReading);
        InstantRange evaluated = range(startOffset,endOffset);
        StatisticsQueryWindow window = new StatisticsQueryWindow(evaluated,evaluated,AS_OF,false);

        RawRangeAvailability actual = resolver.determineRawRangeAvailability(window,history,rawTier(START));

        assertThat(actual).isEqualTo(expected);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "HOURLY,false,false",
            "HOURLY,true,false",
            "HOURLY,false,true",
            "DAILY,false,false",
            "DAILY,true,false",
            "DAILY,false,true"
    })
    void usesWholeVerifiedSummariesAndPreservesTheirTimestamps(StatisticsResolution resolution,boolean empty,boolean beforeRollupDue) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(resolution);
        SensorSummaryAggregate expected = samples(sensor.getType(),empty ? 0 : 2,20.0,0);
        SensorSummary summary = newSummary(resolution,sensor,bucket,expected);
        Instant refreshedAt = bucket.endExclusive().plusSeconds(120);
        summary.refresh(expected,refreshedAt);

        Instant due = beforeRollupDue ? bucket.startInclusive() : bucket.endExclusive();
        StatisticsTierAvailability tier = summaryTier(resolution,retentionCutoff(resolution),
                new InstantRange(CREATED_AT,bucket.endExclusive()),due);
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,AS_OF.minusSeconds(3600)),tier);

        IntervalStatisticsDataPoint point = resolve(resolution,sensor,bucket,bucket,summary,availability);

        assertPoint(point,resolution,sensor,bucket,empty ? NO_SAMPLES : COMPLETE,expected,
                bucket.endExclusive().plusSeconds(60),refreshedAt);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "HOURLY,false",
            "HOURLY,true",
            "DAILY,false",
            "DAILY,true"
    })
    void closedWholeBucketsRequireVerifiedCoverageEvenWhenRawDataOrASummaryExists(StatisticsResolution resolution,boolean hasSummary) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(resolution);
        SensorSummary summary = hasSummary
                ? newSummary(resolution,sensor,bucket,samples(sensor.getType(),2,20.0,0))
                : null;
        StatisticsTierAvailability tier = summaryTier(resolution,retentionCutoff(resolution),null,bucket.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor),tier);

        IntervalStatisticsDataPoint point = resolve(resolution,sensor,bucket,bucket,summary,availability);

        assertPoint(point,resolution,sensor,bucket,ROLLUP_DELAY,null);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "HOURLY,false",
            "HOURLY,true",
            "DAILY,false",
            "DAILY,true"
    })
    void knownEmptyClosedBucketsNeedNeitherRetainedDataNorVerifiedCoverage(StatisticsResolution resolution,boolean firstReadingAtEnd) {
        InstantRange bucket = bucket(resolution);
        Sensor sensor = newSensor(SensorType.MOTION,UTC,firstReadingAtEnd ? bucket.endExclusive() : null);
        StatisticsTierAvailability tier = summaryTier(resolution,bucket.endExclusive(),null,bucket.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,AS_OF.minusSeconds(3600)),tier);

        IntervalStatisticsDataPoint point = resolve(resolution,sensor,bucket,bucket,null,availability);

        assertPoint(point,resolution,sensor,bucket,NO_SAMPLES,SensorSummaryAggregate.emptyBoolean());
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "HOURLY,0",
            "HOURLY,3600",
            "DAILY,0",
            "DAILY,86400"
    })
    void missingWholeVerifiedBucketsExpireAtOrBeforeTheCutoff(StatisticsResolution resolution,long secondsAfterBucketEnd) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(resolution);
        Instant cutoff = bucket.endExclusive().plusSeconds(secondsAfterBucketEnd);
        StatisticsTierAvailability tier = summaryTier(resolution,cutoff,
                new InstantRange(CREATED_AT,bucket.endExclusive()),bucket.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,AS_OF.minusSeconds(3600)),tier);

        IntervalStatisticsDataPoint point = resolve(resolution,sensor,bucket,bucket,null,availability);

        assertPoint(point,resolution,sensor,bucket,EXPIRED,null);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(value = StatisticsResolution.class,names = {"HOURLY","DAILY"})
    void missingRetainedWholeBucketsInsideVerifiedCoverageFailInsteadOfFallingBackToRaw(StatisticsResolution resolution) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(resolution);
        StatisticsAvailabilitySnapshot availability = snapshot(sensor);
        String tierName = resolution == HOURLY ? "hourly" : "daily";

        assertThatIllegalStateException()
                .isThrownBy(() -> resolve(resolution,sensor,bucket,bucket,null,availability))
                .withMessage("Verified " + tierName + " coverage is missing a retained summary row");

        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,false",
            "TEMPERATURE,true",
            "HUMIDITY,false",
            "HUMIDITY,true",
            "MOTION,false",
            "MOTION,true"
    })
    void reconstructsPartialHoursFromRawDataAndClipsTheQueryToTheFirstReading(SensorType type,boolean empty) {
        InstantRange bucket = bucket(HOURLY);
        InstantRange segment = range(900,2700);
        Instant firstReading = empty ? CREATED_AT.plusSeconds(600) : START.plusSeconds(1800);
        Instant queryStart = empty ? segment.startInclusive() : firstReading;
        Sensor sensor = newSensor(type,UTC,firstReading);
        SensorSummaryAggregate expected = samples(type,empty ? 0 : 3,20.2,empty ? 0 : 1);
        SensorSummary summary = newSummary(HOURLY,sensor,bucket,samples(type,100,20.2,60));

        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,queryStart,segment.endExclusive()))
                .thenReturn(new RawAggregate(expected));

        IntervalStatisticsDataPoint point = resolve(HOURLY,sensor,segment,bucket,summary,snapshot(sensor,START));

        assertPoint(point,HOURLY,sensor,segment,empty ? NO_SAMPLES : COMPLETE,expected);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,queryStart,segment.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository);
        verifyNoInteractions(hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void reconstructsWholeProvisionalHoursAndKeepsThemPartialEvenWithoutSamples(boolean empty) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(HOURLY);
        SensorSummaryAggregate expected = samples(sensor.getType(),empty ? 0 : 2,21.0,0);
        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,null,bucket.startInclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,START),hourly);

        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,bucket.startInclusive(),bucket.endExclusive()))
                .thenReturn(new RawAggregate(expected));

        IntervalStatisticsDataPoint point = resolve(HOURLY,sensor,bucket,bucket,null,availability);

        assertPoint(point,HOURLY,sensor,bucket,PARTIAL,expected);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,bucket.startInclusive(),bucket.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository);
        verifyNoInteractions(hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "HOURLY,false",
            "HOURLY,true",
            "DAILY,false",
            "DAILY,true"
    })
    void knownEmptyPartialIntervalsPreserveWhetherTheirBucketIsProvisional(StatisticsResolution resolution,boolean provisional) {
        InstantRange bucket = bucket(resolution);
        InstantRange segment = range(600,1200);
        Sensor sensor = newSensor(SensorType.MOTION,UTC,segment.endExclusive());
        Instant due = provisional ? bucket.startInclusive() : bucket.endExclusive();
        StatisticsTierAvailability tier = summaryTier(resolution,retentionCutoff(resolution),null,due);
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,AS_OF.minusSeconds(3600)),tier);

        IntervalStatisticsDataPoint point = resolve(resolution,sensor,segment,bucket,null,availability);

        assertPoint(point,resolution,sensor,segment,provisional ? PARTIAL : NO_SAMPLES,SensorSummaryAggregate.emptyBoolean());
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "true,false,EXPIRED",
            "false,false,ROLLUP_DELAY",
            "true,true,EXPIRED",
            "false,true,ROLLUP_DELAY"
    })
    void partialHoursWithoutRawDataDistinguishExpirationFromRollupDelay(boolean verified,boolean provisional,StatisticsPointStatus expectedStatus) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(HOURLY);
        InstantRange segment = range(900,2700);
        InstantRange coverage = verified ? new InstantRange(CREATED_AT,bucket.endExclusive()) : null;
        Instant due = provisional ? bucket.startInclusive() : bucket.endExclusive();
        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,coverage,due);
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,bucket.endExclusive()),hourly);
        SensorSummary summary = newSummary(HOURLY,sensor,bucket,samples(sensor.getType(),2,20.0,0));

        IntervalStatisticsDataPoint point = resolve(HOURLY,sensor,segment,bucket,summary,availability);

        assertPoint(point,HOURLY,sensor,segment,expectedStatus,null);
        verifyNoInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void partialDaysCombineVerifiedHoursWithOnlyTheRequestedRawBoundaryFragments(SensorType type) {
        ZoneId timeZone = ZoneId.of("Asia/Kathmandu");
        LocalDate localDate = LocalDate.of(2026,4,15);
        Sensor sensor = newSensor(type,timeZone,CREATED_AT.plusSeconds(600));
        InstantRange bucket = new InstantRange(localDate.atStartOfDay(timeZone).toInstant(),
                localDate.plusDays(1).atStartOfDay(timeZone).toInstant());
        Instant completeHoursStart = Instant.parse("2026-04-14T19:00:00Z");
        Instant completeHoursEnd = completeHoursStart.plusSeconds(7200);
        InstantRange segment = new InstantRange(completeHoursStart.minusSeconds(1800),completeHoursEnd.plusSeconds(1200));

        SensorSummaryAggregate leading = samples(type,1,10.0,1);
        SensorSummaryAggregate firstHourAggregate = samples(type,2,20.0,1);
        SensorSummaryAggregate secondHourAggregate = samples(type,3,30.0,2);
        SensorSummaryAggregate trailing = samples(type,4,40.0,0);
        HourlySensorSummary firstHour = HourlySensorSummary.create(sensor,completeHoursStart,
                firstHourAggregate,completeHoursStart.plusSeconds(3660));
        HourlySensorSummary secondHour = HourlySensorSummary.create(sensor,completeHoursStart.plusSeconds(3600),
                secondHourAggregate,completeHoursEnd.plusSeconds(60));

        SensorSummaryAggregate wholeDayAggregate = type == SensorType.MOTION
                ? SensorSummaryAggregate.booleanSamples(100,60)
                : SensorSummaryAggregate.numeric(100,unit(type),new BigDecimal("7500.0"),10.0,80.0);
        SensorSummary wholeDaySummary = newSummary(DAILY,sensor,bucket,wholeDayAggregate);
        StatisticsAvailabilitySnapshot availability = snapshot(sensor,completeHoursStart.minusSeconds(3600));

        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),completeHoursStart))
                .thenReturn(new RawAggregate(leading));
        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,completeHoursStart,completeHoursEnd))
                .thenReturn(List.of(firstHour,secondHour));
        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,completeHoursEnd,segment.endExclusive()))
                .thenReturn(new RawAggregate(trailing));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,segment,bucket,wholeDaySummary,availability);

        SensorSummaryAggregate expected = type == SensorType.MOTION
                ? SensorSummaryAggregate.booleanSamples(10,4)
                : SensorSummaryAggregate.numeric(10,unit(type),new BigDecimal("300.0"),10.0,40.0);
        assertPoint(point,DAILY,sensor,segment,COMPLETE,expected);
        assertThat(point.localDateStart()).isEqualTo(localDate);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),completeHoursStart);
        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,completeHoursStart,completeHoursEnd);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,completeHoursEnd,segment.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "600,2400",
            "600,3600",
            "0,2400"
    })
    void dailyFragmentsContainedInOneUtcHourQueryOnlyTheirRawRange(long startOffset,long endOffset) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange segment = range(startOffset,endOffset);
        SensorSummaryAggregate expected = samples(sensor.getType(),2,20.0,0);

        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),segment.endExclusive()))
                .thenReturn(new RawAggregate(expected));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,segment,bucket(DAILY),null,snapshot(sensor,START));

        assertPoint(point,DAILY,sensor,segment,COMPLETE,expected);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),segment.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository);
        verifyNoInteractions(hourlySensorSummaryRepository);
    }



    @Test
    void dailyFragmentsAcrossAnHourBoundaryAvoidAnEmptyHourlyQuery() {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange segment = range(3000,4200);
        Instant boundary = START.plusSeconds(3600);
        SensorSummaryAggregate leading = samples(sensor.getType(),1,10.0,0);
        SensorSummaryAggregate trailing = samples(sensor.getType(),2,20.0,0);

        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),boundary))
                .thenReturn(new RawAggregate(leading));
        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,boundary,segment.endExclusive()))
                .thenReturn(new RawAggregate(trailing));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,segment,bucket(DAILY),null,snapshot(sensor,START));

        SensorSummaryAggregate expected = SensorSummaryAggregate.numeric(3,MeasurementUnit.C,new BigDecimal("50.0"),10.0,20.0);
        assertPoint(point,DAILY,sensor,segment,COMPLETE,expected);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,segment.startInclusive(),boundary);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,boundary,segment.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository);
        verifyNoInteractions(hourlySensorSummaryRepository);
    }



    @ParameterizedTest
    @ValueSource(booleans = {false,true})
    void dailyReconstructionUsesRawForUnverifiedHoursEvenWhenAnOldSummaryExists(boolean hasOldSummary) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange hour = bucket(HOURLY);
        SensorSummaryAggregate expected = samples(sensor.getType(),2,20.0,0);
        HourlySensorSummary oldSummary = HourlySensorSummary.create(sensor,START,
                samples(sensor.getType(),1,20.0,0),hour.endExclusive().plusSeconds(60));
        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,
                new InstantRange(CREATED_AT,START),hour.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,START),hourly);

        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,START,hour.endExclusive()))
                .thenReturn(hasOldSummary ? List.of(oldSummary) : List.of());
        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,START,hour.endExclusive()))
                .thenReturn(new RawAggregate(expected));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,hour,bucket(DAILY),null,availability);

        assertPoint(point,DAILY,sensor,hour,COMPLETE,expected);
        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,START,hour.endExclusive());
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,START,hour.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @Test
    void dailyReconstructionFailsWhenAVerifiedRetainedSourceHourIsMissing() {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange hour = bucket(HOURLY);
        StatisticsAvailabilitySnapshot availability = snapshot(sensor,START);

        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,START,hour.endExclusive()))
                .thenReturn(List.of());

        assertThatIllegalStateException()
                .isThrownBy(() -> resolve(DAILY,sensor,hour,bucket(DAILY),null,availability))
                .withMessage("Verified hourly coverage is missing a retained summary row");

        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,START,hour.endExclusive());
        verifyNoMoreInteractions(hourlySensorSummaryRepository);
        verifyNoInteractions(sensorReadingRepository);
    }



    @Test
    void dailyReconstructionReportsExpirationWhenAMissingVerifiedSourceHourEndsAtTheCutoff() {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange hour = bucket(HOURLY);
        Instant cutoff = hour.endExclusive();
        StatisticsTierAvailability hourly = summaryTier(HOURLY,cutoff,
                new InstantRange(CREATED_AT,hour.endExclusive()),hour.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(snapshot(sensor,cutoff),hourly);

        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,cutoff,START,hour.endExclusive()))
                .thenReturn(List.of());

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,hour,bucket(DAILY),null,availability);

        assertPoint(point,DAILY,sensor,hour,EXPIRED,null);
        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,cutoff,START,hour.endExclusive());
        verifyNoMoreInteractions(hourlySensorSummaryRepository);
        verifyNoInteractions(sensorReadingRepository);
    }



    @ParameterizedTest
    @CsvSource({
            "true,false,EXPIRED",
            "false,false,ROLLUP_DELAY",
            "true,true,EXPIRED",
            "false,true,ROLLUP_DELAY"
    })
    void dailyUnavailableSourcesDiscardAvailableMetricsAndExpirationTakesPrecedence(boolean firstHourVerified,boolean provisional,StatisticsPointStatus expectedStatus) {
        Sensor sensor = newSensor(SensorType.TEMPERATURE);
        InstantRange bucket = bucket(DAILY);
        InstantRange segment = range(1800,9000);
        Instant completeHoursStart = START.plusSeconds(3600);
        Instant completeHoursEnd = START.plusSeconds(7200);
        Instant coveredUntil = firstHourVerified ? completeHoursStart : START;
        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,
                new InstantRange(CREATED_AT,coveredUntil),completeHoursEnd);
        StatisticsTierAvailability daily = summaryTier(DAILY,DAILY_CUTOFF,null,
                provisional ? bucket.startInclusive() : bucket.endExclusive());
        StatisticsAvailabilitySnapshot availability = withTier(
                withTier(snapshot(sensor,completeHoursEnd),hourly),daily);
        SensorSummaryAggregate trailing = samples(sensor.getType(),3,20.0,0);

        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,completeHoursStart,completeHoursEnd))
                .thenReturn(List.of());
        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,completeHoursEnd,segment.endExclusive()))
                .thenReturn(new RawAggregate(trailing));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,segment,bucket,null,availability);

        assertPoint(point,DAILY,sensor,segment,expectedStatus,null);
        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,completeHoursStart,completeHoursEnd);
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,completeHoursEnd,segment.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    @Test
    void wholeProvisionalDaysCombineKnownEmptyHistoryVerifiedHoursAndAnUnrolledRawTail() {
        InstantRange bucket = bucket(DAILY);
        Instant firstReading = START.plusSeconds(22 * 3600);
        Instant rawStart = START.plusSeconds(23 * 3600);
        Sensor sensor = newSensor(SensorType.TEMPERATURE,UTC,firstReading);
        SensorSummaryAggregate hourlyAggregate = samples(sensor.getType(),2,20.0,0);
        SensorSummaryAggregate rawAggregate = samples(sensor.getType(),3,30.0,0);
        HourlySensorSummary summary = HourlySensorSummary.create(sensor,firstReading,hourlyAggregate,rawStart.plusSeconds(60));

        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,
                new InstantRange(firstReading,rawStart),bucket.endExclusive());
        StatisticsTierAvailability daily = summaryTier(DAILY,DAILY_CUTOFF,null,bucket.startInclusive());
        StatisticsAvailabilitySnapshot availability = withTier(withTier(snapshot(sensor,rawStart),hourly),daily);

        when(hourlySensorSummaryRepository.findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,bucket.startInclusive(),bucket.endExclusive()))
                .thenReturn(List.of(summary));
        when(sensorReadingRepository.aggregateForSummaryRange(SENSOR_ID,rawStart,bucket.endExclusive()))
                .thenReturn(new RawAggregate(rawAggregate));

        IntervalStatisticsDataPoint point = resolve(DAILY,sensor,bucket,bucket,null,availability);

        SensorSummaryAggregate expected = SensorSummaryAggregate.numeric(5,MeasurementUnit.C,new BigDecimal("130.0"),20.0,30.0);
        assertPoint(point,DAILY,sensor,bucket,PARTIAL,expected);
        verify(hourlySensorSummaryRepository).findForStatisticsRange(SENSOR_ID,HOURLY_CUTOFF,bucket.startInclusive(),bucket.endExclusive());
        verify(sensorReadingRepository).aggregateForSummaryRange(SENSOR_ID,rawStart,bucket.endExclusive());
        verifyNoMoreInteractions(sensorReadingRepository,hourlySensorSummaryRepository);
    }



    private IntervalStatisticsDataPoint resolve(StatisticsResolution resolution,Sensor sensor,InstantRange segment,
                                                InstantRange bucket,SensorSummary summary,StatisticsAvailabilitySnapshot availability) {
        if (resolution == HOURLY) {
            return resolver.resolveHourlyInterval(sensor,segment,bucket,(HourlySensorSummary) summary,availability);
        }

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        LocalDate localDate = bucket.startInclusive().atZone(timeZone).toLocalDate();
        return resolver.resolveDailyInterval(sensor,segment,bucket,localDate,timeZone,(DailySensorSummary) summary,availability);
    }



    private static Sensor newSensor(SensorType type) {
        return newSensor(type,UTC,CREATED_AT.plusSeconds(600));
    }



    private static Sensor newSensor(SensorType type,ZoneId timeZone,Instant firstReadingAt) {
        Sensor sensor = new Sensor(user(),type,"Living room","Istanbul","Kadikoy","Window",timeZone.getId(),CREATED_AT);
        setField(sensor,"id",SENSOR_ID);

        if (firstReadingAt != null) {
            sensor.recordFirstReading(firstReadingAt,firstReadingAt);
        }

        return sensor;
    }



    private static InstantRange range(long startOffset,long endOffset) {
        return new InstantRange(START.plusSeconds(startOffset),START.plusSeconds(endOffset));
    }



    private static InstantRange bucket(StatisticsResolution resolution) {
        return range(0,resolution == HOURLY ? 3600 : 86400);
    }



    private static Instant retentionCutoff(StatisticsResolution resolution) {
        return resolution == HOURLY ? HOURLY_CUTOFF : DAILY_CUTOFF;
    }



    private static MeasurementUnit unit(SensorType type) {
        return type == SensorType.HUMIDITY ? MeasurementUnit.PERCENT : MeasurementUnit.C;
    }



    private static SensorSummaryAggregate samples(SensorType type,long count,double value,long trueCount) {
        if (type == SensorType.MOTION) {
            return SensorSummaryAggregate.booleanSamples(count,trueCount);
        }

        if (count == 0) {
            return SensorSummaryAggregate.emptyNumeric(unit(type));
        }

        BigDecimal sum = BigDecimal.valueOf(value).multiply(BigDecimal.valueOf(count));
        return SensorSummaryAggregate.numeric(count,unit(type),sum,value,value);
    }



    private static SensorSummary newSummary(StatisticsResolution resolution,Sensor sensor,InstantRange bucket,SensorSummaryAggregate aggregate) {
        Instant finalizedAt = bucket.endExclusive().plusSeconds(60);

        if (resolution == HOURLY) {
            return HourlySensorSummary.create(sensor,bucket.startInclusive(),aggregate,finalizedAt);
        }

        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        LocalDate localDate = bucket.startInclusive().atZone(timeZone).toLocalDate();
        return DailySensorSummary.create(sensor,localDate,timeZone,aggregate,finalizedAt);
    }



    private static StatisticsAvailabilitySnapshot snapshot(Sensor sensor) {
        return snapshot(sensor,START.minus(Duration.ofDays(2)));
    }



    private static StatisticsAvailabilitySnapshot snapshot(Sensor sensor,Instant rawFrom) {
        SensorHistory history = SensorHistory.from(sensor);
        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        Instant dailyEnd = AS_OF.atZone(timeZone).toLocalDate().atStartOfDay(timeZone).toInstant();
        InstantRange hourlyCoverage = history.hourlyCoverageOrigin()
                .map(origin -> new InstantRange(origin,AS_OF)).orElse(null);
        InstantRange dailyCoverage = history.dailyCoverageOrigin(timeZone)
                .map(origin -> new InstantRange(origin,dailyEnd)).orElse(null);

        StatisticsTierAvailability hourly = summaryTier(HOURLY,HOURLY_CUTOFF,hourlyCoverage,AS_OF);
        StatisticsTierAvailability daily = summaryTier(DAILY,DAILY_CUTOFF,dailyCoverage,dailyEnd);
        return new StatisticsAvailabilitySnapshot(history,rawTier(rawFrom),hourly,daily);
    }



    private static StatisticsTierAvailability rawTier(Instant retainedFrom) {
        InstantRange retained = new InstantRange(retainedFrom,AS_OF);
        Optional<InstantRange> represented = retained.isEmpty() ? Optional.empty() : Optional.of(retained);
        return new StatisticsTierAvailability(RAW,new StatisticsTierRetention(retainedFrom,retained),represented,Optional.empty());
    }



    private static StatisticsTierAvailability summaryTier(StatisticsResolution resolution,Instant cutoff,InstantRange coverage,Instant due) {
        InstantRange retained = new InstantRange(cutoff,AS_OF);
        Optional<InstantRange> verified = Optional.ofNullable(coverage);
        Optional<InstantRange> represented = verified.flatMap(range -> range.intersection(retained));
        Duration lag = coverage != null && coverage.endExclusive().isBefore(due)
                ? Duration.between(coverage.endExclusive(),due)
                : Duration.ZERO;
        StatisticsRollupProgress progress = new StatisticsRollupProgress(verified,due,lag);

        return new StatisticsTierAvailability(resolution,new StatisticsTierRetention(cutoff,retained),represented,Optional.of(progress));
    }



    private static StatisticsAvailabilitySnapshot withTier(StatisticsAvailabilitySnapshot snapshot,StatisticsTierAvailability tier) {
        StatisticsTierAvailability hourly = tier.resolution() == HOURLY ? tier : snapshot.hourly();
        StatisticsTierAvailability daily = tier.resolution() == DAILY ? tier : snapshot.daily();
        return new StatisticsAvailabilitySnapshot(snapshot.history(),snapshot.raw(),hourly,daily);
    }



    private static void assertPoint(IntervalStatisticsDataPoint actual,StatisticsResolution resolution,Sensor sensor,
                                    InstantRange interval,StatisticsPointStatus status,SensorSummaryAggregate aggregate) {
        assertPoint(actual,resolution,sensor,interval,status,aggregate,null,null);
    }



    private static void assertPoint(IntervalStatisticsDataPoint actual,StatisticsResolution resolution,Sensor sensor,
                                    InstantRange interval,StatisticsPointStatus status,SensorSummaryAggregate aggregate,Instant finalizedAt,Instant refreshedAt) {
        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        LocalDate localDate = resolution == DAILY ? interval.startInclusive().atZone(timeZone).toLocalDate() : null;
        IntervalStatisticsDataPoint expected = new IntervalStatisticsDataPoint(interval,localDate,
                localDate == null ? null : localDate.plusDays(1),localDate == null ? null : timeZone.getId(),
                status,aggregate,finalizedAt,refreshedAt);

        assertThat(actual).usingRecursiveComparison().isEqualTo(expected);
    }



    private record RawAggregate(SensorSummaryAggregate aggregate) implements RawSensorReadingAggregateProjection {

        @Override
        public long getSourceSampleCount() {
            return aggregate.getSourceSampleCount();
        }

        @Override
        public BigDecimal getNumericSum() {
            return aggregate.getNumericSum();
        }

        @Override
        public Double getNumericMinimum() {
            return aggregate.getNumericMinimum();
        }

        @Override
        public Double getNumericMaximum() {
            return aggregate.getNumericMaximum();
        }

        @Override
        public long getTrueSampleCount() {
            Long trueCount = aggregate.getTrueSampleCount();
            return trueCount == null ? 0 : trueCount;
        }
    }
}