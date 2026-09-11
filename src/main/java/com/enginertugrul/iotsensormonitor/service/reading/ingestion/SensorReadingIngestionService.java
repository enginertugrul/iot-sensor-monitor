package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.service.alert.AlertEvaluationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Function;



@Service
public class SensorReadingIngestionService {

    private final SensorIngestionAccessService sensorIngestionAccessService;
    private final SensorReadingRepository readingRepository;
    private final AlertEvaluationService alertEvaluationService;
    private final Clock clock;


    public SensorReadingIngestionService(SensorIngestionAccessService sensorIngestionAccessService,SensorReadingRepository readingRepository,AlertEvaluationService alertEvaluationService,Clock clock) {
        this.sensorIngestionAccessService = sensorIngestionAccessService;
        this.readingRepository = readingRepository;
        this.alertEvaluationService = alertEvaluationService;
        this.clock = clock;
    }



    @Transactional
    public void ingestTemperature(String sensorToken,double celsiusValue,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.TEMPERATURE,sensor -> SensorReading.temperature(sensor,celsiusValue,recordedAt));
    }



    @Transactional
    public void ingestHumidity(String sensorToken,double humidityPercentage,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.HUMIDITY,sensor -> SensorReading.humidity(sensor,humidityPercentage,recordedAt));
    }



    @Transactional
    public void ingestMotion(String sensorToken,boolean motionDetected,Instant recordedAt) {
        ingestReading(sensorToken,SensorType.MOTION,sensor -> SensorReading.motion(sensor,motionDetected,recordedAt));
    }



    private void ingestReading(String sensorToken, SensorType expectedType ,Function<Sensor,SensorReading> readingFactory) {
        Sensor sensor = sensorIngestionAccessService.requireActiveSensor(sensorToken,expectedType);

        SensorReading reading;

        try {
            reading = readingFactory.apply(sensor);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSensorReadingException(exception.getMessage(),exception);
        }

        sensor.recordFirstReading(reading.getRecordedAt(),clock.instant());
        readingRepository.save(reading);
        alertEvaluationService.evaluateReading(reading);
    }

}