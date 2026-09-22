package com.enginertugrul.iotsensormonitor.entity.reading.summary;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.sensor;
import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class HourlySensorSummaryTest {

    private static final Instant BUCKET_START = CREATED_AT;
    private static final Instant BUCKET_END = BUCKET_START.plusSeconds(3600);




    @ParameterizedTest
    @EnumSource(SensorType.class)
    void createsSummaryAndRestoresItsAggregateForEverySensorType(SensorType type) {
        Sensor sensor = sensor(type);
        SensorSummaryAggregate aggregate = aggregate(type,false);

        HourlySensorSummary summary = HourlySensorSummary.create(sensor,BUCKET_START,aggregate,BUCKET_END);

        assertThat(summary.getId()).isNull();
        assertThat(summary.getSensor()).isSameAs(sensor);
        assertThat(summary.getBucketStart()).isEqualTo(BUCKET_START);
        assertThat(summary.getBucketEnd()).isEqualTo(BUCKET_END);
        assertThat(summary.getFinalizedAt()).isEqualTo(BUCKET_END);
        assertThat(summary.getRefreshedAt()).isEqualTo(BUCKET_END);
        assertAggregate(summary,aggregate);
    }



    @ParameterizedTest
    @ValueSource(strings = {"UTC","Europe/Istanbul","Asia/Kathmandu","America/New_York"})
    void usesUtcHoursRegardlessOfSensorTimezone(String timeZoneId) {
        Sensor sensor = new Sensor(user(),SensorType.TEMPERATURE,"Living room","Istanbul","Kadikoy","Window",timeZoneId,CREATED_AT);

        HourlySensorSummary summary = HourlySensorSummary.create(sensor,BUCKET_START,aggregate(SensorType.TEMPERATURE,false),BUCKET_END);

        assertThat(summary.getBucketStart()).isEqualTo(BUCKET_START);
        assertThat(summary.getBucketEnd()).isEqualTo(BUCKET_END);
    }



    @ParameterizedTest
    @ValueSource(strings = {
            "2026-01-15T12:00:00.000000001Z",
            "2026-01-15T12:00:01Z",
            "2026-01-15T12:01:00Z",
            "2026-01-15T12:15:00Z"
    })
    void rejectsBucketStartsThatAreNotUtcHourBoundaries(String value) {
        Instant bucketStart = Instant.parse(value);
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,bucketStart,aggregate,bucketStart.plusSeconds(3600)))
                .withMessage("bucketStart must be aligned to a UTC hour");
    }



    @Test
    void rejectsMissingCreationArguments() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);

        assertThatNullPointerException()
                .isThrownBy(() -> HourlySensorSummary.create(null,BUCKET_START,aggregate,BUCKET_END))
                .withMessage("sensor must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,null,aggregate,BUCKET_END))
                .withMessage("bucketStart must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,BUCKET_START,null,BUCKET_END))
                .withMessage("aggregate must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,BUCKET_START,aggregate,null))
                .withMessage("finalizedAt must not be null");
    }



    @Test
    void rejectsFinalizationImmediatelyBeforeBucketEnd() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,BUCKET_START,aggregate,BUCKET_END.minusNanos(1)))
                .withMessage("finalizedAt must not be before bucketEnd");
    }



    @ParameterizedTest
    @EnumSource(SensorType.class)
    void refreshReplacesEmptyAndPopulatedAggregatesWithoutChangingBucketIdentity(SensorType type) {
        Sensor sensor = sensor(type);
        SensorSummaryAggregate empty = aggregate(type,true);
        SensorSummaryAggregate populated = aggregate(type,false);
        Instant refreshedAt = BUCKET_END.plusSeconds(60);
        HourlySensorSummary summary = HourlySensorSummary.create(sensor,BUCKET_START,empty,BUCKET_END);

        assertAggregate(summary,empty);

        summary.refresh(populated,refreshedAt);

        assertAggregate(summary,populated);
        SensorSummaryAggregate snapshot = summary.toAggregate();

        summary.refresh(empty,refreshedAt);

        assertAggregate(summary,empty);
        assertThat(snapshot).usingRecursiveComparison().isEqualTo(populated);
        assertThat(summary.getSensor()).isSameAs(sensor);
        assertThat(summary.getBucketStart()).isEqualTo(BUCKET_START);
        assertThat(summary.getBucketEnd()).isEqualTo(BUCKET_END);
        assertThat(summary.getFinalizedAt()).isEqualTo(BUCKET_END);
        assertThat(summary.getRefreshedAt()).isEqualTo(refreshedAt);
    }



    @Test
    void rejectsRefreshBeforeLatestRefreshWithoutChangingState() {
        SensorSummaryAggregate populated = aggregate(SensorType.TEMPERATURE,false);
        SensorSummaryAggregate empty = aggregate(SensorType.TEMPERATURE,true);
        Instant refreshedAt = BUCKET_END.plusSeconds(60);
        HourlySensorSummary summary = HourlySensorSummary.create(sensor(SensorType.TEMPERATURE),BUCKET_START,populated,BUCKET_END);
        summary.refresh(empty,refreshedAt);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> summary.refresh(populated,refreshedAt.minusNanos(1)))
                .withMessage("refreshedAt must not move backwards");

        assertAggregate(summary,empty);
        assertThat(summary.getFinalizedAt()).isEqualTo(BUCKET_END);
        assertThat(summary.getRefreshedAt()).isEqualTo(refreshedAt);
    }



    @Test
    void rejectsMissingRefreshArgumentsWithoutChangingState() {
        SensorSummaryAggregate aggregate = aggregate(SensorType.TEMPERATURE,false);
        HourlySensorSummary summary = HourlySensorSummary.create(sensor(SensorType.TEMPERATURE),BUCKET_START,aggregate,BUCKET_END);

        assertThatNullPointerException()
                .isThrownBy(() -> summary.refresh(null,BUCKET_END.plusSeconds(60)))
                .withMessage("aggregate must not be null");

        assertThatNullPointerException()
                .isThrownBy(() -> summary.refresh(aggregate,null))
                .withMessage("refreshedAt must not be null");

        assertAggregate(summary,aggregate);
        assertThat(summary.getRefreshedAt()).isEqualTo(BUCKET_END);
    }



    @Test
    void rejectsIncompatibleAggregateKindsAndUnitsDuringCreationAndRefresh() {
        Sensor sensor = sensor(SensorType.TEMPERATURE);
        SensorSummaryAggregate original = aggregate(SensorType.TEMPERATURE,false);
        SensorSummaryAggregate wrongKind = SensorSummaryAggregate.emptyBoolean();
        SensorSummaryAggregate wrongUnit = SensorSummaryAggregate.emptyNumeric(MeasurementUnit.PERCENT);
        HourlySensorSummary summary = HourlySensorSummary.create(sensor,BUCKET_START,original,BUCKET_END);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,BUCKET_START,wrongKind,BUCKET_END))
                .withMessage("Summary aggregate value kind does not match sensor type TEMPERATURE");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> HourlySensorSummary.create(sensor,BUCKET_START,wrongUnit,BUCKET_END))
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