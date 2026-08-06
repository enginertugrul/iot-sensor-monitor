package com.enginertugrul.iotsensormonitor.service.reading.ingestion;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.InactiveSensorException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iotsensormonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.security.ingestion.SensorIngestionTokenGenerator;
import org.springframework.stereotype.Component;




@Component
public class SensorIngestionAccessService {

    private final SensorRepository sensorRepository;
    private final SensorIngestionTokenGenerator tokenGenerator;

    public SensorIngestionAccessService(SensorRepository sensorRepository, SensorIngestionTokenGenerator tokenGenerator) {
        this.sensorRepository = sensorRepository;
        this.tokenGenerator = tokenGenerator;
    }

    public Sensor requireActiveSensor(String rawToken, SensorType expectedType) {

        String hashedToken = tokenGenerator.hash(rawToken);

        Sensor sensor = sensorRepository.findByIngestionTokenHashForUpdate(hashedToken)
                .orElseThrow(InvalidSensorTokenException::new);

        if (!sensor.isActive()) {
            throw new InactiveSensorException();
        }

        if (sensor.getType() != expectedType) {
            throw new InvalidSensorReadingException("Reading does not match the registered sensor type");
        }

        return sensor;
    }
}