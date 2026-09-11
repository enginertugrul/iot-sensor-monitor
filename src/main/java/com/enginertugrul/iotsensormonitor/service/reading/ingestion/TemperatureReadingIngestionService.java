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




@Service
public class TemperatureReadingIngestionService {


    private final SensorIngestionAccessService sensorIngestionAccessService;
    private final SensorReadingRepository readingRepository;
    private final AlertEvaluationService alertEvaluationService;
    private final Clock clock;



    public TemperatureReadingIngestionService(SensorIngestionAccessService sensorIngestionAccessService, SensorReadingRepository readingRepository, AlertEvaluationService alertEvaluationService, Clock clock) {
        this.sensorIngestionAccessService = sensorIngestionAccessService;
        this.readingRepository = readingRepository;
        this.alertEvaluationService = alertEvaluationService;
        this.clock = clock;
    }




    @Transactional
    public void ingest(String sensorToken, double celsiusValue, Instant recordedAt) {

        Sensor sensor = sensorIngestionAccessService.requireActiveSensor(sensorToken, SensorType.TEMPERATURE);

        SensorReading reading;

        try {
            reading = SensorReading.temperature(sensor, celsiusValue, recordedAt);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSensorReadingException(exception.getMessage(), exception);
        }

        sensor.recordFirstReading(recordedAt, clock.instant());
        readingRepository.save(reading);

        alertEvaluationService.evaluateReading(reading);
    }
}