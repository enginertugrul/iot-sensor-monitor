package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.measurement.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.alert.AlertEvaluationService;
import com.enginertugrul.iotsensormonitor.testsupport.TestFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static com.enginertugrul.iotsensormonitor.testsupport.TestFixtures.CREATED_AT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;



@ExtendWith(MockitoExtension.class)
class SensorReadingIngestionServiceTest {

    private static final Long SENSOR_ID = 17L;
    private static final String RAW_TOKEN = "test-only-ingestion-token";
    private static final Instant ACCEPTED_AT = CREATED_AT.plus(3,ChronoUnit.DAYS);
    private static final Instant BEFORE_INGESTION = ACCEPTED_AT.minusSeconds(60);
    private static final Instant RECORDED_AT = ACCEPTED_AT.minusSeconds(16200).plusNanos(123456000);
    private static final Instant REQUESTED_AT = RECORDED_AT.plusNanos(789);
    private static final Instant HOUR_START = Instant.parse("2026-01-18T07:00:00Z");
    private static final Instant LOCAL_DAY_START = Instant.parse("2026-01-17T08:00:00Z");

    @Mock
    private SensorIngestionAccessService accessService;

    @Mock
    private SensorReadingRepository readingRepository;

    @Mock
    private SensorRollupCheckpointRepository checkpointRepository;

    @Mock
    private SensorReadingIngestionPolicy ingestionPolicy;

    @Mock
    private AlertEvaluationService alertEvaluationService;

    private SensorReadingIngestionService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(ACCEPTED_AT,ZoneOffset.UTC);
        service = new SensorReadingIngestionService(accessService,readingRepository,checkpointRepository,ingestionPolicy,alertEvaluationService,clock);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,23.5,,C",
            "HUMIDITY,58.0,,PERCENT",
            "MOTION,,true,",
            "MOTION,,false,"
    })
    void savesTypedReadingWithValidatedTimestampBeforeEvaluatingAlerts(SensorType type,Double numericValue,Boolean booleanValue,MeasurementUnit unit) {
        Sensor sensor = acceptReading(type,"UTC",REQUESTED_AT,RECORDED_AT);

        ingest(type,numericValue,booleanValue);

        ArgumentCaptor<SensorReading> captor = ArgumentCaptor.forClass(SensorReading.class);
        InOrder calls = inOrder(accessService,ingestionPolicy,checkpointRepository,readingRepository,alertEvaluationService);
        calls.verify(accessService).requireActiveSensor(RAW_TOKEN,type);
        calls.verify(ingestionPolicy).requireValidRecordedAt(sensor,REQUESTED_AT,ACCEPTED_AT);
        calls.verify(checkpointRepository).findBySensorIdAndStageForUpdate(SENSOR_ID,RollupStage.RAW_TO_HOURLY);
        calls.verify(checkpointRepository).findBySensorIdAndStageForUpdate(SENSOR_ID,RollupStage.HOURLY_TO_DAILY);
        calls.verify(readingRepository).save(captor.capture());
        calls.verify(alertEvaluationService).evaluateReading(captor.getValue());
        calls.verifyNoMoreInteractions();

        SensorReading reading = captor.getValue();
        assertThat(reading.getSensor()).isSameAs(sensor);
        assertThat(reading.getNumericValue()).isEqualTo(numericValue);
        assertThat(reading.getBooleanValue()).isEqualTo(booleanValue);
        assertThat(reading.getUnit()).isEqualTo(unit);
        assertThat(reading.getRecordedAt()).isEqualTo(RECORDED_AT);
        assertThat(sensor.getFirstReadingAt()).isEqualTo(RECORDED_AT);
        assertThat(sensor.getUpdatedAt()).isEqualTo(ACCEPTED_AT);
    }



    @ParameterizedTest
    @ValueSource(longs = {-60,0,60})
    void changesFirstReadingAndUpdatedAtOnlyForAnEarlierReading(long offsetSeconds) {
        Instant candidate = RECORDED_AT.plusSeconds(offsetSeconds);
        Sensor sensor = acceptReading(SensorType.TEMPERATURE,"UTC",candidate,candidate);
        sensor.recordFirstReading(RECORDED_AT,BEFORE_INGESTION);

        service.ingestTemperature(RAW_TOKEN,22.5,candidate);

        assertThat(sensor.getFirstReadingAt()).isEqualTo(offsetSeconds < 0 ? candidate : RECORDED_AT);
        assertThat(sensor.getUpdatedAt()).isEqualTo(offsetSeconds < 0 ? ACCEPTED_AT : BEFORE_INGESTION);
        SensorReading reading = savedReading();
        assertThat(reading.getRecordedAt()).isEqualTo(candidate);
        verify(alertEvaluationService).evaluateReading(reading);
    }



    @Test
    void invalidatesUtcHourAndSensorLocalDayAndClearsPreviousProgress() {
        Sensor sensor = acceptReading(SensorType.TEMPERATURE,"America/Los_Angeles",REQUESTED_AT,RECORDED_AT);
        SensorRollupCheckpoint hourly = completedCheckpoint(sensor,RollupStage.RAW_TO_HOURLY,HOUR_START.minusSeconds(3600),HOUR_START.plusSeconds(7200));
        SensorRollupCheckpoint daily = completedCheckpoint(sensor,RollupStage.HOURLY_TO_DAILY,LOCAL_DAY_START.minusSeconds(86400),LOCAL_DAY_START.plusSeconds(86400));
        returnCheckpoints(hourly,daily);

        service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT);

        assertInvalidated(hourly,HOUR_START.minusSeconds(3600),HOUR_START);
        assertInvalidated(daily,LOCAL_DAY_START.minusSeconds(86400),LOCAL_DAY_START);
        assertThat(sensor.getFirstReadingAt()).isEqualTo(RECORDED_AT);
        SensorReading reading = savedReading();
        verify(alertEvaluationService).evaluateReading(reading);
    }



    @Test
    void movesCoverageStartBackWhenReadingPredatesInitializedCheckpoints() {
        Sensor sensor = acceptReading(SensorType.TEMPERATURE,"America/Los_Angeles",REQUESTED_AT,RECORDED_AT);
        SensorRollupCheckpoint hourly = SensorRollupCheckpoint.initialize(sensor,RollupStage.RAW_TO_HOURLY,HOUR_START.plusSeconds(3600),BEFORE_INGESTION);
        SensorRollupCheckpoint daily = SensorRollupCheckpoint.initialize(sensor,RollupStage.HOURLY_TO_DAILY,LOCAL_DAY_START.plusSeconds(86400),BEFORE_INGESTION);
        returnCheckpoints(hourly,daily);

        service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT);

        assertInvalidated(hourly,HOUR_START,HOUR_START);
        assertInvalidated(daily,LOCAL_DAY_START,LOCAL_DAY_START);
    }



    @ParameterizedTest
    @ValueSource(ints = {-1,0})
    void preservesCheckpointsWhenReadingBucketIsAtOrAfterCoveredUntil(int offset) {
        Sensor sensor = acceptReading(SensorType.TEMPERATURE,"America/Los_Angeles",REQUESTED_AT,RECORDED_AT);
        Instant hourlyEnd = HOUR_START.plusSeconds(offset * 3600L);
        Instant dailyEnd = LOCAL_DAY_START.plusSeconds(offset * 86400L);
        SensorRollupCheckpoint hourly = completedCheckpoint(sensor,RollupStage.RAW_TO_HOURLY,hourlyEnd.minusSeconds(3600),hourlyEnd);
        SensorRollupCheckpoint daily = completedCheckpoint(sensor,RollupStage.HOURLY_TO_DAILY,dailyEnd.minusSeconds(86400),dailyEnd);
        returnCheckpoints(hourly,daily);

        service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT);

        assertUnchangedCheckpoint(hourly,hourlyEnd.minusSeconds(3600),hourlyEnd);
        assertUnchangedCheckpoint(daily,dailyEnd.minusSeconds(86400),dailyEnd);
        SensorReading reading = savedReading();
        verify(alertEvaluationService).evaluateReading(reading);
    }



    @Test
    void stopsBeforeTimestampValidationWhenAccessFails() {
        InvalidSensorTokenException failure = new InvalidSensorTokenException();
        when(accessService.requireActiveSensor(RAW_TOKEN,SensorType.TEMPERATURE)).thenThrow(failure);

        assertThatThrownBy(() -> service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT)).isSameAs(failure);

        verifyNoInteractions(ingestionPolicy,checkpointRepository,readingRepository,alertEvaluationService);
    }




    @Test
    void stopsWithoutChangingHistoryWhenTimestampPolicyRejectsReading() {
        Sensor sensor = newSensor(SensorType.TEMPERATURE,"UTC");
        InvalidSensorReadingException failure = new InvalidSensorReadingException("Rejected timestamp");
        when(accessService.requireActiveSensor(RAW_TOKEN,SensorType.TEMPERATURE)).thenReturn(sensor);
        when(ingestionPolicy.requireValidRecordedAt(sensor,REQUESTED_AT,ACCEPTED_AT)).thenThrow(failure);

        assertThatThrownBy(() -> service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT)).isSameAs(failure);

        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
        verifyNoInteractions(checkpointRepository,readingRepository,alertEvaluationService);
    }



    @ParameterizedTest
    @CsvSource({
            "TEMPERATURE,-273.16",
            "TEMPERATURE,NaN",
            "TEMPERATURE,Infinity",
            "HUMIDITY,-0.1",
            "HUMIDITY,100.1",
            "HUMIDITY,NaN",
            "HUMIDITY,Infinity"
    })
    void translatesInvalidNumericValuesBeforeChangingHistoryOrCheckpoints(SensorType type,double value) {
        Sensor sensor = acceptReading(type,"UTC",REQUESTED_AT,RECORDED_AT);

        assertThatThrownBy(() -> ingest(type,value,null))
                .isExactlyInstanceOf(InvalidSensorReadingException.class)
                .hasCauseInstanceOf(IllegalArgumentException.class);

        assertThat(sensor.getFirstReadingAt()).isNull();
        assertThat(sensor.getUpdatedAt()).isEqualTo(CREATED_AT);
        verifyNoInteractions(checkpointRepository,readingRepository,alertEvaluationService);
    }



    @ParameterizedTest
    @EnumSource(RollupStage.class)
    void propagatesCheckpointFailureWithoutSavingOrEvaluatingReading(RollupStage stage) {
        acceptReading(SensorType.TEMPERATURE,"UTC",REQUESTED_AT,RECORDED_AT);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Checkpoint lookup failed");

        if (stage == RollupStage.HOURLY_TO_DAILY) {
            when(checkpointRepository.findBySensorIdAndStageForUpdate(SENSOR_ID,RollupStage.RAW_TO_HOURLY))
                    .thenReturn(Optional.empty());
        }
        doThrow(failure).when(checkpointRepository).findBySensorIdAndStageForUpdate(SENSOR_ID,stage);

        assertThatThrownBy(() -> service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT)).isSameAs(failure);

        verify(checkpointRepository).findBySensorIdAndStageForUpdate(SENSOR_ID,stage);
        verifyNoInteractions(readingRepository,alertEvaluationService);
    }



    @Test
    void propagatesSaveFailureWithoutEvaluatingAlerts() {
        acceptReading(SensorType.TEMPERATURE,"UTC",REQUESTED_AT,RECORDED_AT);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Reading save failed");
        when(readingRepository.save(any(SensorReading.class))).thenThrow(failure);

        assertThatThrownBy(() -> service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT)).isSameAs(failure);

        verifyNoInteractions(alertEvaluationService);
    }



    @Test
    void propagatesAlertFailureAfterSavingTheSameReading() {
        acceptReading(SensorType.TEMPERATURE,"UTC",REQUESTED_AT,RECORDED_AT);
        IllegalStateException failure = new IllegalStateException("Alert evaluation failed");
        doThrow(failure).when(alertEvaluationService).evaluateReading(any(SensorReading.class));

        assertThatThrownBy(() -> service.ingestTemperature(RAW_TOKEN,22.5,REQUESTED_AT)).isSameAs(failure);

        ArgumentCaptor<SensorReading> captor = ArgumentCaptor.forClass(SensorReading.class);
        InOrder calls = inOrder(readingRepository,alertEvaluationService);
        calls.verify(readingRepository).save(captor.capture());
        calls.verify(alertEvaluationService).evaluateReading(captor.getValue());
    }



    private Sensor acceptReading(SensorType type,String timezone,Instant requestedAt,Instant validatedAt) {
        Sensor sensor = newSensor(type,timezone);
        when(accessService.requireActiveSensor(RAW_TOKEN,type)).thenReturn(sensor);
        when(ingestionPolicy.requireValidRecordedAt(sensor,requestedAt,ACCEPTED_AT)).thenReturn(validatedAt);
        return sensor;
    }



    private static Sensor newSensor(SensorType type,String timezone) {
        Sensor sensor = TestFixtures.sensor(type);
        sensor.updateDetails("Living room","Istanbul","Kadikoy","Window",timezone,CREATED_AT);
        ReflectionTestUtils.setField(sensor,"id",SENSOR_ID);
        return sensor;
    }



    private void ingest(SensorType type,Double numericValue,Boolean booleanValue) {
        switch (type) {
            case TEMPERATURE -> service.ingestTemperature(RAW_TOKEN,numericValue,REQUESTED_AT);
            case HUMIDITY -> service.ingestHumidity(RAW_TOKEN,numericValue,REQUESTED_AT);
            case MOTION -> service.ingestMotion(RAW_TOKEN,booleanValue,REQUESTED_AT);
        }
    }



    private SensorReading savedReading() {
        ArgumentCaptor<SensorReading> captor = ArgumentCaptor.forClass(SensorReading.class);
        verify(readingRepository).save(captor.capture());
        return captor.getValue();
    }



    private void returnCheckpoints(SensorRollupCheckpoint hourly,SensorRollupCheckpoint daily) {
        when(checkpointRepository.findBySensorIdAndStageForUpdate(SENSOR_ID,RollupStage.RAW_TO_HOURLY)).thenReturn(Optional.of(hourly));
        when(checkpointRepository.findBySensorIdAndStageForUpdate(SENSOR_ID,RollupStage.HOURLY_TO_DAILY)).thenReturn(Optional.of(daily));
    }



    private static SensorRollupCheckpoint completedCheckpoint(Sensor sensor,RollupStage stage,Instant start,Instant end) {
        SensorRollupCheckpoint checkpoint = SensorRollupCheckpoint.initialize(sensor,stage,start,BEFORE_INGESTION);
        long bucketSeconds = stage == RollupStage.RAW_TO_HOURLY ? 3600 : 86400;
        while (checkpoint.getCoveredUntil().isBefore(end)) {
            Instant bucketStart = checkpoint.getCoveredUntil();
            checkpoint.recordAttempt(bucketStart,BEFORE_INGESTION);
            checkpoint.advanceContiguously(bucketStart,bucketStart.plusSeconds(bucketSeconds),BEFORE_INGESTION);
        }
        return checkpoint;
    }



    private static void assertInvalidated(SensorRollupCheckpoint checkpoint,Instant coverageStart,Instant coveredUntil) {
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(coverageStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(coveredUntil);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isNull();
        assertThat(checkpoint.getLastAttemptedAt()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isNull();
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isNull();
        assertThat(checkpoint.getLastSuccessfulAt()).isNull();
        assertThat(checkpoint.getLastAdvancedAt()).isNull();
        assertThat(checkpoint.getCreatedAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(ACCEPTED_AT);
    }



    private static void assertUnchangedCheckpoint(SensorRollupCheckpoint checkpoint,Instant coverageStart,Instant coveredUntil) {
        assertThat(checkpoint.getCoverageStartedAt()).isEqualTo(coverageStart);
        assertThat(checkpoint.getCoveredUntil()).isEqualTo(coveredUntil);
        assertThat(checkpoint.getLastAttemptedBucketStart()).isEqualTo(coverageStart);
        assertThat(checkpoint.getLastAttemptedAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getLastSuccessfulBucketStart()).isEqualTo(coverageStart);
        assertThat(checkpoint.getLastSuccessfulBucketEnd()).isEqualTo(coveredUntil);
        assertThat(checkpoint.getLastSuccessfulAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getLastAdvancedAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getCreatedAt()).isEqualTo(BEFORE_INGESTION);
        assertThat(checkpoint.getUpdatedAt()).isEqualTo(BEFORE_INGESTION);
    }
}