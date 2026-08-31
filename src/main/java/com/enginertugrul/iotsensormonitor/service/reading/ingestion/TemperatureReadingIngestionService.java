package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.service.alert.AlertEvaluationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;




@Service
public class TemperatureReadingIngestionService {


    private final SensorIngestionAccessService sensorIngestionAccessService;
    private final SensorReadingRepository readingRepository;
    private final AlertEvaluationService alertEvaluationService;



    public TemperatureReadingIngestionService(SensorIngestionAccessService sensorIngestionAccessService, SensorReadingRepository readingRepository, AlertEvaluationService alertEvaluationService) {
        this.sensorIngestionAccessService = sensorIngestionAccessService;
        this.readingRepository = readingRepository;
        this.alertEvaluationService = alertEvaluationService;
    }




    @Transactional
    public void ingest(String sensorToken, double celsiusValue) {

        Sensor sensor = sensorIngestionAccessService.requireActiveSensor(sensorToken, SensorType.TEMPERATURE);

        Instant recordedAt = Instant.now();
        SensorReading reading;

        try {
            reading = SensorReading.temperature(sensor, celsiusValue, recordedAt);
        } catch (IllegalArgumentException exception) {
            throw new InvalidSensorReadingException(exception.getMessage(), exception);
        }

        sensor.recordFirstReading(recordedAt);
        readingRepository.save(reading);

        alertEvaluationService.evaluateReading(reading);
    }
}