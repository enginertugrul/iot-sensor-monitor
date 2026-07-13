package com.enginertugrul.iottemperaturemonitor.service.reading.ingestion;

import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iottemperaturemonitor.repository.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class HumidityReadingIngestionService {

    private final SensorIngestionAccessService sensorIngestionAccessService;
    private final SensorReadingRepository readingRepository;

    public HumidityReadingIngestionService(
            SensorIngestionAccessService sensorIngestionAccessService,
            SensorReadingRepository readingRepository
    ) {
        this.sensorIngestionAccessService = sensorIngestionAccessService;
        this.readingRepository = readingRepository;
    }

    @Transactional
    public void ingest(
            String sensorToken,
            double humidityPercentage
    ) {
        Sensor sensor = sensorIngestionAccessService.requireActiveSensor(
                sensorToken,
                SensorType.HUMIDITY
        );

        Instant recordedAt = Instant.now();
        SensorReading reading;

        try {
            reading = SensorReading.humidity(
                    sensor,
                    humidityPercentage,
                    recordedAt
            );
        } catch (IllegalArgumentException exception) {
            throw new InvalidSensorReadingException(
                    exception.getMessage(),
                    exception
            );
        }

        sensor.markSeen(recordedAt);
        readingRepository.save(reading);
    }
}