package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRollupCheckpointRepository;
import com.enginertugrul.iotsensormonitor.service.alert.AlertEvaluationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.function.BiFunction;



@Service
public class SensorReadingIngestionService {

    private final SensorIngestionAccessService sensorIngestionAccessService;
    private final SensorReadingRepository readingRepository;
    private final SensorRollupCheckpointRepository checkpointRepository;
    private final SensorReadingIngestionPolicy ingestionPolicy;
    private final AlertEvaluationService alertEvaluationService;
    private final Clock clock;

    public SensorReadingIngestionService(SensorIngestionAccessService sensorIngestionAccessService, SensorReadingRepository readingRepository, SensorRollupCheckpointRepository checkpointRepository, SensorReadingIngestionPolicy ingestionPolicy, AlertEvaluationService alertEvaluationService, Clock clock) {
        this.sensorIngestionAccessService = sensorIngestionAccessService;
        this.readingRepository = readingRepository;
        this.checkpointRepository = checkpointRepository;
        this.ingestionPolicy = ingestionPolicy;
        this.alertEvaluationService = alertEvaluationService;
        this.clock = clock;
    }



    @Transactional
    public void ingestTemperature(String sensorToken,double celsiusValue,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.TEMPERATURE,recordedAt,(sensor,timestamp) -> SensorReading.temperature(sensor,celsiusValue,timestamp));
    }



    @Transactional
    public void ingestHumidity(String sensorToken,double humidityPercentage,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.HUMIDITY,recordedAt,(sensor,timestamp) -> SensorReading.humidity(sensor,humidityPercentage,timestamp));
    }



    @Transactional
    public void ingestMotion(String sensorToken,boolean motionDetected,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.MOTION,recordedAt,(sensor,timestamp) -> SensorReading.motion(sensor,motionDetected,timestamp));
    }

    private void ingestReading(String sensorToken, SensorType expectedType, Instant recordedAt, BiFunction<Sensor,Instant,SensorReading> readingFactory) {

        Sensor sensor = sensorIngestionAccessService.requireActiveSensor(sensorToken,expectedType);
        Instant acceptedAt = clock.instant();
        Instant validRecordedAt = ingestionPolicy.requireValidRecordedAt(sensor,recordedAt,acceptedAt);

        SensorReading reading;

        try {
            reading = readingFactory.apply(sensor,validRecordedAt);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSensorReadingException(exception.getMessage(),exception);
        }

        if (sensor.wouldUpdateFirstReading(validRecordedAt)) {
            sensor.recordFirstReading(validRecordedAt,acceptedAt);
        }

        invalidateRollupCoverageIfAffected(sensor,validRecordedAt,acceptedAt);

        readingRepository.save(reading);
        alertEvaluationService.evaluateReading(reading);
    }



    private void invalidateRollupCoverageIfAffected(Sensor sensor,Instant recordedAt,Instant acceptedAt) {
        Instant hourStart = recordedAt.truncatedTo(ChronoUnit.HOURS);
        ZoneId timeZone = ZoneId.of(sensor.getTimezone());
        Instant dayStart = recordedAt.atZone(timeZone).toLocalDate().atStartOfDay(timeZone).toInstant();

        checkpointRepository.findBySensorIdAndStageForUpdate(sensor.getId(),RollupStage.RAW_TO_HOURLY)
                .filter(checkpoint -> checkpoint.requiresInvalidationFrom(hourStart))
                .ifPresent(checkpoint -> checkpoint.invalidateFrom(hourStart,acceptedAt));

        checkpointRepository.findBySensorIdAndStageForUpdate(sensor.getId(),RollupStage.HOURLY_TO_DAILY)
                .filter(checkpoint -> checkpoint.requiresInvalidationFrom(dayStart))
                .ifPresent(checkpoint -> checkpoint.invalidateFrom(dayStart,acceptedAt));
    }

}