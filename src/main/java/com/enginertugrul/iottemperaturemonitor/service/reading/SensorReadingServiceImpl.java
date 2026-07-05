package com.enginertugrul.iottemperaturemonitor.service.reading;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorDailyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorViewDTO;
import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.exception.InvalidSensorTokenException;
import com.enginertugrul.iottemperaturemonitor.repository.SensorReadingRepository;
import com.enginertugrul.iottemperaturemonitor.repository.SensorRepository;
import com.enginertugrul.iottemperaturemonitor.security.ingestion.SensorIngestionTokenGenerator;
import com.enginertugrul.iottemperaturemonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@Service
public class SensorReadingServiceImpl implements SensorReadingService {


    private final SensorReadingRepository sensorReadingRepository;
    private final SensorRepository sensorRepository;
    private final SensorIngestionTokenGenerator sensorIngestionTokenGenerator;
    private final TemperatureUnitConverter temperatureUnitConverter;


    public SensorReadingServiceImpl(SensorReadingRepository sensorReadingRepository, SensorRepository sensorRepository, SensorIngestionTokenGenerator sensorIngestionTokenGenerator, TemperatureUnitConverter temperatureUnitConverter) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.sensorRepository = sensorRepository;
        this.sensorIngestionTokenGenerator = sensorIngestionTokenGenerator;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }

    @Override
    @Transactional
    public void saveTemperatureReading(String sensorToken, Double celsiusValue) {

        String hashedToken = sensorIngestionTokenGenerator.hash(sensorToken);

        Sensor sensor = sensorRepository.findByIngestionTokenHash(hashedToken)
                .orElseThrow(InvalidSensorTokenException::new);

        if (!sensor.isActive()) {
            throw new IllegalArgumentException("Sensor is not active");
        }

        if (sensor.getType() != SensorType.TEMPERATURE) {
            throw new IllegalArgumentException("Sensor is not a temperature sensor");
        }

        Instant now = Instant.now();

        SensorReading reading = SensorReading.temperature(sensor, celsiusValue, now);
        sensor.markSeen(now);
        sensorReadingRepository.save(reading);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorViewDTO> getRecentTenRecords(Long sensorId, Long ownerId , TemperatureUnit temperatureUnit) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);

       return sensorReadingRepository.findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDesc(sensorId, ownerId)
                .stream()
                .map(reading -> toViewDTO(reading, sensor, temperatureUnit))
                .toList();

    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorDailyAverageDTO> getDailyAverageForNumericValueFromLastWeek(Long sensorId, Long ownerId, TemperatureUnit temperatureUnit) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);
        ZoneId zoneId = ZoneId.of(sensor.getTimezone());
        LocalDate today = LocalDate.now(zoneId);
        Instant untilDate = today.minusDays(6).atStartOfDay(zoneId).toInstant();

        List<SensorDailyAverageDTO> dbResults =
                sensorReadingRepository.findDailyAverageValuesSince(sensorId, untilDate, sensor.getTimezone());

        Map<LocalDate, Double> dataMap = dbResults.stream()
                .collect(Collectors.toMap(SensorDailyAverageDTO::date,
                dto -> temperatureUnitConverter.convertFromCelsius( dto.averageTemperature(), temperatureUnit)
                ));

        List<SensorDailyAverageDTO> result = new ArrayList<>();

        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            result.add(new SensorDailyAverageDTO(date, dataMap.getOrDefault(date, 0.0)));
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorHourlyAverageDTO> getHourlyAverageForDate(Long sensorId, Long ownerId, LocalDate date, TemperatureUnit temperatureUnit) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);
        ZoneId zoneId = ZoneId.of(sensor.getTimezone());

        Instant startOfDay = date.atStartOfDay(zoneId).toInstant();
        Instant endOfDay = date.plusDays(1).atStartOfDay(zoneId).toInstant();

        List<SensorHourlyAverageDTO> dbResults =
                sensorReadingRepository.findHourlyAverageValuesForDate(
                        sensorId,
                        startOfDay,
                        endOfDay,
                        sensor.getTimezone()
                );

        Map<Short, Double> dataMap = dbResults.stream()
                .collect(Collectors.toMap(SensorHourlyAverageDTO::hour, dto ->
                        temperatureUnitConverter.convertFromCelsius(dto.average(), temperatureUnit) ));

        List<SensorHourlyAverageDTO> result = new ArrayList<>();

        for (short hour = 0; hour <= 23; hour++) {
            result.add(new SensorHourlyAverageDTO(hour,  dataMap.get(hour)) );
        }

        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDate getTodayForSensor(Long sensorId, Long ownerId) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);
        return LocalDate.now(ZoneId.of(sensor.getTimezone()));
    }

    private Sensor getOwnedSensor(Long sensorId, Long ownerId) {
        return sensorRepository.findByIdAndOwnerId(sensorId, ownerId)
                .orElseThrow(() -> new NoSuchElementException("Sensor not found"));
    }

    private SensorViewDTO toViewDTO(SensorReading reading, Sensor sensor, TemperatureUnit temperatureUnit) {
        Double convertedTemperatureValue = temperatureUnitConverter.convertFromCelsius(reading.getNumericValue(), temperatureUnit);
        return new SensorViewDTO(
                sensor.getHomeLocation(),
                convertedTemperatureValue,
                reading.getRecordedAt().atZone(ZoneId.of(sensor.getTimezone()))
        );
    }
}
