package com.enginertugrul.iotsensormonitor.service.reading;

import com.enginertugrul.iotsensormonitor.dto.reading.RecentSensorReadingsDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingSnapshotDTO;
import com.enginertugrul.iotsensormonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import com.enginertugrul.iotsensormonitor.repository.SensorReadingRepository;
import com.enginertugrul.iotsensormonitor.repository.SensorRepository;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;


@Service
public class SensorReadingServiceImpl implements SensorReadingService {


    private final SensorReadingRepository sensorReadingRepository;
    private final SensorRepository sensorRepository;
    private final TemperatureUnitConverter temperatureUnitConverter;


    public SensorReadingServiceImpl(SensorReadingRepository sensorReadingRepository, SensorRepository sensorRepository, TemperatureUnitConverter temperatureUnitConverter) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.sensorRepository = sensorRepository;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }





    @Override
    @Transactional(readOnly = true)
    public List<SensorReadingViewDTO> getRecentReadings(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);

        return sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDescIdDesc(sensorId,ownerId)
                .stream()
                .map(reading -> toViewDTO(reading, sensor, temperatureUnit))
                .toList();
    }



    @Override
    @Transactional(readOnly = true)
    public RecentSensorReadingsDTO getRecentReadingsSnapshot(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit) {
        List<SensorReadingSnapshotDTO> readings = getRecentReadings(sensorId,ownerId,temperatureUnit).stream()
                .map(this::toSnapshotDTO)
                .toList();

        return new RecentSensorReadingsDTO(sensorId,readings);
    }



    private SensorReadingSnapshotDTO toSnapshotDTO(SensorReadingViewDTO reading) {
        ZonedDateTime timestamp = reading.timestamp();
        String timestampText = timestamp.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        return new SensorReadingSnapshotDTO(reading.sensorType(),reading.installationLocation(),
                reading.numericValue(),reading.booleanValue(),reading.unitSymbol(),timestampText,
                timestamp.getZone().getId(),timestamp.getOffset().getId());
    }





    private SensorReadingViewDTO toViewDTO(SensorReading reading, Sensor sensor, TemperatureUnit temperatureUnit) {

        return new SensorReadingViewDTO(
                sensor.getType(),
                sensor.getInstallationLocation(),
                toDisplayValue(sensor.getType(), reading.getNumericValue(), temperatureUnit),
                reading.getBooleanValue(),
                getDisplayUnitSymbol(sensor.getType(), temperatureUnit),
                reading.getRecordedAt().atZone(ZoneId.of(sensor.getTimezone()))
        );
    }






    private Double toDisplayValue(SensorType sensorType, Double canonicalValue, TemperatureUnit temperatureUnit) {

        if (canonicalValue == null) {
            return null;
        }

        if (sensorType == SensorType.TEMPERATURE) {
            return temperatureUnitConverter.convertFromCelsius(canonicalValue, temperatureUnit);
        }

        return canonicalValue;
    }






    private String getDisplayUnitSymbol(SensorType sensorType, TemperatureUnit temperatureUnit) {

        return switch (sensorType) {
            case TEMPERATURE ->temperatureUnitConverter.getSymbol(temperatureUnit);
            case HUMIDITY -> "% RH";
            case MOTION -> "";
        };
    }








    private Sensor getOwnedSensor(Long sensorId, Long ownerId) {

        return sensorRepository.findByIdAndOwnerId(sensorId, ownerId)
                .orElseThrow(SensorNotFoundException::new);
    }



}