package com.enginertugrul.iottemperaturemonitor.service.reading.ingestion;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.exception.InactiveSensorException;
import com.enginertugrul.iottemperaturemonitor.exception.InvalidSensorReadingException;
import com.enginertugrul.iottemperaturemonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iottemperaturemonitor.repository.SensorRepository;
import com.enginertugrul.iottemperaturemonitor.security.ingestion.SensorIngestionTokenGenerator;
import org.springframework.stereotype.Component;

@Component
public class SensorIngestionAccessService {

    private final SensorRepository sensorRepository;
    private final SensorIngestionTokenGenerator tokenGenerator;

    public SensorIngestionAccessService(
            SensorRepository sensorRepository,
            SensorIngestionTokenGenerator tokenGenerator
    ) {
        this.sensorRepository = sensorRepository;
        this.tokenGenerator = tokenGenerator;
    }

    public Sensor requireActiveSensor(
            String rawToken,
            SensorType expectedType
    ) {
        String hashedToken = tokenGenerator.hash(rawToken);

        Sensor sensor = sensorRepository
                .findByIngestionTokenHash(hashedToken)
                .orElseThrow(InvalidSensorTokenException::new);

        if (!sensor.isActive()) {
            throw new InactiveSensorException();
        }

        if (sensor.getType() != expectedType) {
            throw new InvalidSensorReadingException(
                    "Reading does not match the registered sensor type"
            );
        }

        return sensor;
    }
}