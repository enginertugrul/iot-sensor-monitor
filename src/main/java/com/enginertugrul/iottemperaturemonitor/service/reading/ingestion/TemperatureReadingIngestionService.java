package com.enginertugrul.iottemperaturemonitor.service.reading.ingestion;

import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iottemperaturemonitor.repository.SensorReadingRepository;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertEvaluationService;
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

        sensor.markSeen(recordedAt);
        readingRepository.save(reading);

        alertEvaluationService.evaluateReading(reading);
    }
}