package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class DailySensorSummaryTest {

    private static final LocalDate LOCAL_DATE = LocalDate.of(2026,1,16);
    private static final ZoneId TIME_ZONE = ZoneId.of("Europe/Istanbul");
    private static final Instant BUCKET_START = Instant.parse("2026-01-15T21:00:00Z");
    private static final Instant BUCKET_END = Instant.parse("2026-01-16T21:00:00Z");



    @ParameterizedTest
    @CsvSource({
            "UTC,2026-01-16,2026-01-16T00:00:00Z,2026-01-17T00:00:00Z,24",
            "Europe/Istanbul,2026-01-16,2026-01-15T21:00:00Z,2026-01-16T21:00:00Z,24",
            "Asia/Kathmandu,2026-01-16,2026-01-15T18:15:00Z,2026-01-16T18:15:00Z,24",
            "Europe/Berlin,2026-03-29,2026-03-28T23:00:00Z,2026-03-29T22:00:00Z,23",
            "Europe/Berlin,2026-10-25,2026-10-24T22:00:00Z,2026-10-25T23:00:00Z,25"
    })
    void derivesLocalDayBoundariesIncludingDstAndFractionalHourOffsets(String zoneId,String date,String start,String end,long hours) {
        ZoneId timeZone = ZoneId.of(zoneId);
        LocalDate localDate = LocalDate.parse(date);
        Instant expectedStart = Instant.parse(start);
        Instant expectedEnd = Instant.parse(end);
        Sensor sensor = sensorInZone(SensorType.TEMPERATURE,timeZone);
        SensorSummaryAggregate aggregate = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.C);

        DailySensorSummary summary = DailySensorSummary.create(sensor,localDate,timeZone,aggregate,expectedEnd);

        assertThat(summary.getSensor()).isSameAs(sensor);
        assertThat(summary.getLocalDate()).isEqualTo(localDate);
        assertThat(summary.getTimeZoneId()).isEqualTo(zoneId);
        assertThat(summary.getTimeZone()).isEqualTo(timeZone);
        assertThat(summary.getBucketStart()).isEqualTo(expectedStart);
        assertThat(summary.getBucketEnd()).isEqualTo(expectedEnd);
        assertThat(Duration.between(summary.getBucketStart(),summary.getBucketEnd())).isEqualTo(Duration.ofHours(hours));
        assertThat(summary.getFinalizedAt()).isEqualTo(expectedEnd);
        assertThat(summary.getRefreshedAt()).isEqualTo(expectedEnd);
        assertAggregate(summary,aggregate);
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void createsSummaryAndRestoresItsAggregateForEverySensorType(SensorType type) {
        Sensor sensor = sensorInZone(type,TIME_ZONE);
        SensorSummaryAggregate aggregate = aggregate(type,false);

        DailySensorSummary summary = DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,aggregate,BUCKET_END);

        assertThat(summary.getId()).isNull();
        assertThat(summary.getSensor()).isSameAs(sensor);
        assertThat(summary.getBucketStart()).isEqualTo(BUCKET_START);
        assertThat(summary.getBucketEnd()).isEqualTo(BUCKET_END);
        assertAggregate(summary,aggregate);
    }



    @Test
    void rejectsMissingCreationArguments() {
        Sensor sensor = sensorInZone(SensorType.TEMPERATURE,TIME_ZONE);
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);

        assertThatNullPointerException()
                .isThrownBy(() -> DailySensorSummary.create(null,LOCAL_DATE,TIME_ZONE,aggregate,BUCKET_END))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,null,TIME_ZONE,aggregate,BUCKET_END))
                .withMessage("localDate must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,null,aggregate,BUCKET_END))
                .withMessage("timeZone must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,null,BUCKET_END))
                .withMessage("aggregate must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,aggregate,null))
                .withMessage("finalizedAt must not be null");
    }



    @Test
    void rejectsFinalizationImmediatelyBeforeLocalDayEnds() {
        Sensor sensor = sensorInZone(SensorType.TEMPERATURE,TIME_ZONE);
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,aggregate,BUCKET_END.minusNanos(1)))
                .withMessage("finalizedAt must not be before bucketEnd");
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void refreshClearsAndRepopulatesAggregatesWhilePreservingLocalDayMetadata(SensorType type) {
        Sensor sensor = sensorInZone(type,TIME_ZONE);
        SensorSummaryAggregate populated = aggregate(type,false);
        SensorSummaryAggregate empty = aggregate(type,true);
        Instant refreshedAt = BUCKET_END.plusSeconds(60);
        DailySensorSummary summary = DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,populated,BUCKET_END);

        summary.refresh(empty,refreshedAt);

        assertAggregate(summary,empty);

        summary.refresh(populated,refreshedAt);

        assertAggregate(summary,populated);
        assertThat(summary.getSensor()).isSameAs(sensor);
        assertThat(summary.getLocalDate()).isEqualTo(LOCAL_DATE);
        assertThat(summary.getTimeZoneId()).isEqualTo(TIME_ZONE.getId());
        assertThat(summary.getTimeZone()).isEqualTo(TIME_ZONE);
        assertThat(summary.getBucketStart()).isEqualTo(BUCKET_START);
        assertThat(summary.getBucketEnd()).isEqualTo(BUCKET_END);
        assertThat(summary.getFinalizedAt()).isEqualTo(BUCKET_END);
        assertThat(summary.getRefreshedAt()).isEqualTo(refreshedAt);
    }



    @Test
    void rejectsInvalidRefreshArgumentsWithoutChangingState() {
        Sensor sensor = sensorInZone(SensorType.TEMPERATURE,TIME_ZONE);
        SensorSummaryAggregate populated = aggregate(SensorType.TEMPERATURE,false);
        SensorSummaryAggregate empty = aggregate(SensorType.TEMPERATURE,true);
        Instant refreshedAt = BUCKET_END.plusSeconds(60);
        DailySensorSummary summary = DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,populated,BUCKET_END);
        summary.refresh(empty,refreshedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> summary.refresh(populated,refreshedAt.minusNanos(1)))
                .withMessage("refreshedAt must not move backwards");

        assertThatNullPointerException()
                .isThrownBy(() -> summary.refresh(null,refreshedAt))
                .withMessage("aggregate must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> summary.refresh(populated,null))
                .withMessage("refreshedAt must not be null");

        assertAggregate(summary,empty);
        assertThat(summary.getFinalizedAt()).isEqualTo(BUCKET_END);
        assertThat(summary.getRefreshedAt()).isEqualTo(refreshedAt);
    }



    @Test
    void rejectsIncompatibleAggregateKindsAndUnitsDuringCreationAndRefresh() {
        Sensor sensor = sensorInZone(SensorType.TEMPERATURE,TIME_ZONE);
        SensorSummaryAggregate original = aggregate(SensorType.TEMPERATURE,false);
        SensorSummaryAggregate wrongKind = SensorSummaryAggregate.emptyBoolean();
        SensorSummaryAggregate wrongUnit = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT);
        DailySensorSummary summary = DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,original,BUCKET_END);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,wrongKind,BUCKET_END))
                .withMessage("Summary aggregate value kind does not match sensor type TEMPERATURE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DailySensorSummary.create(sensor,LOCAL_DATE,TIME_ZONE,wrongUnit,BUCKET_END))
                .withMessage("Summary aggregate unit does not match sensor type TEMPERATURE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> summary.refresh(wrongKind,BUCKET_END.plusSeconds(60)))
                .withMessage("Summary aggregate value kind does not match sensor type TEMPERATURE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> summary.refresh(wrongUnit,BUCKET_END.plusSeconds(60)))
                .withMessage("Summary aggregate unit does not match sensor type TEMPERATURE");

        assertAggregate(summary,original);
        assertThat(summary.getRefreshedAt()).isEqualTo(BUCKET_END);
    }



    private static Sensor sensorInZone(SensorType type,ZoneId timeZone) {
        return new Sensor(user(),type,"Living room","Istanbul","Kadikoy","Window",timeZone.getId(),CREATED_AT);
    }



    private static SensorSummaryAggregate aggregate(SensorType type,boolean empty) {
        if (type == SensorType.MOTION) {
            return empty ? SensorSummaryAggregate.emptyBoolean() : SensorSummaryAggregate.booleanSamples(4,3);
        }

        MeasurementUnit unit = type == SensorType.TEMPERATURE ? MeasurementUnit.C : MeasurementUnit.PERCENT;
        return empty ? SensorSummaryAggregate.emptyNumeric(unit) : SensorSummaryAggregate.numeric(3,unit,new BigDecimal("60.600"),10.1,30.3);
    }



    private static void assertAggregate(SensorSummary summary,SensorSummaryAggregate expected) {
        assertThat(summary.getSourceSampleCount()).isEqualTo(expected.getSourceSampleCount());
        assertThat(summary.getUnit()).isEqualTo(expected.getUnit());
        assertThat(summary.getNumericSum()).isEqualTo(expected.getNumericSum());
        assertThat(summary.getNumericMinimum()).isEqualTo(expected.getNumericMinimum());
        assertThat(summary.getNumericMaximum()).isEqualTo(expected.getNumericMaximum());
        assertThat(summary.getTrueSampleCount()).isEqualTo(expected.getTrueSampleCount());
        assertThat(summary.toAggregate()).usingRecursiveComparison().isEqualTo(expected);
    }
}