package com.enginertugrul.iottemperaturemonitor.service.reading;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorDailyStatisticDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyStatisticDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorReadingViewDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorStatisticsDTO;
import com.enginertugrul.iottemperaturemonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.repository.DailySensorStatisticProjection;
import com.enginertugrul.iottemperaturemonitor.repository.HourlySensorStatisticProjection;
import com.enginertugrul.iottemperaturemonitor.repository.SensorReadingRepository;
import com.enginertugrul.iottemperaturemonitor.repository.SensorRepository;
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
    private final TemperatureUnitConverter temperatureUnitConverter;


    public SensorReadingServiceImpl(SensorReadingRepository sensorReadingRepository, SensorRepository sensorRepository, TemperatureUnitConverter temperatureUnitConverter) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.sensorRepository = sensorRepository;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorReadingViewDTO> getRecentReadings(
            Long sensorId,
            Long ownerId,
            TemperatureUnit temperatureUnit
    ) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);

        return sensorReadingRepository
                .findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDesc(
                        sensorId,
                        ownerId
                )
                .stream()
                .map(reading -> toViewDTO(
                        reading,
                        sensor,
                        temperatureUnit
                ))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public SensorStatisticsDTO getStatistics(
            Long sensorId,
            Long ownerId,
            TemperatureUnit temperatureUnit
    ) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);
        ZoneId zoneId = ZoneId.of(sensor.getTimezone());
        LocalDate today = LocalDate.now(zoneId);

        return new SensorStatisticsDTO(
                sensor.getType(),
                getDisplayUnitSymbol(
                        sensor.getType(),
                        temperatureUnit
                ),
                today,
                getLastSevenDays(
                        sensor,
                        today,
                        temperatureUnit
                ),
                getHourlyStatistics(
                        sensor,
                        today,
                        temperatureUnit
                )
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<SensorHourlyStatisticDTO>
    getHourlyStatisticsForDate(
            Long sensorId,
            Long ownerId,
            LocalDate date,
            TemperatureUnit temperatureUnit
    ) {
        Sensor sensor = getOwnedSensor(sensorId, ownerId);

        return getHourlyStatistics(
                sensor,
                date,
                temperatureUnit
        );
    }

    private List<SensorDailyStatisticDTO> getLastSevenDays(
            Sensor sensor,
            LocalDate today,
            TemperatureUnit temperatureUnit
    ) {
        ZoneId zoneId = ZoneId.of(sensor.getTimezone());

        Instant startInclusive = today
                .minusDays(6)
                .atStartOfDay(zoneId)
                .toInstant();

        Instant endExclusive = today
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant();

        List<DailySensorStatisticProjection> databaseResults;

        if (sensor.getType() == SensorType.MOTION) {
            databaseResults =
                    sensorReadingRepository
                            .findDailyMotionDetectionCounts(
                                    sensor.getId(),
                                    startInclusive,
                                    endExclusive,
                                    sensor.getTimezone()
                            );
        } else {
            databaseResults =
                    sensorReadingRepository.findDailyNumericStatistics(
                            sensor.getId(),
                            getCanonicalUnit(sensor.getType()).name(),
                            startInclusive,
                            endExclusive,
                            sensor.getTimezone()
                    );
        }

        Map<LocalDate, Double> valuesByDate =
                databaseResults.stream()
                        .collect(Collectors.toMap(
                                DailySensorStatisticProjection::getDate,
                                result -> toDisplayValue(
                                        sensor.getType(),
                                        result.getValue(),
                                        temperatureUnit
                                )
                        ));

        List<SensorDailyStatisticDTO> result =
                new ArrayList<>(7);

        for (int daysAgo = 6; daysAgo >= 0; daysAgo--) {
            LocalDate date = today.minusDays(daysAgo);
            Double value = valuesByDate.get(date);

            if (value == null
                    && sensor.getType() == SensorType.MOTION) {
                value = 0.0;
            }

            result.add(
                    new SensorDailyStatisticDTO(date, value)
            );
        }

        return result;
    }

    private List<SensorHourlyStatisticDTO> getHourlyStatistics(
            Sensor sensor,
            LocalDate date,
            TemperatureUnit temperatureUnit
    ) {
        ZoneId zoneId = ZoneId.of(sensor.getTimezone());

        Instant startInclusive = date
                .atStartOfDay(zoneId)
                .toInstant();

        Instant endExclusive = date
                .plusDays(1)
                .atStartOfDay(zoneId)
                .toInstant();

        List<HourlySensorStatisticProjection> databaseResults;

        if (sensor.getType() == SensorType.MOTION) {
            databaseResults =
                    sensorReadingRepository
                            .findHourlyMotionDetectionCounts(
                                    sensor.getId(),
                                    startInclusive,
                                    endExclusive,
                                    sensor.getTimezone()
                            );
        } else {
            databaseResults =
                    sensorReadingRepository
                            .findHourlyNumericStatistics(
                                    sensor.getId(),
                                    getCanonicalUnit(
                                            sensor.getType()
                                    ).name(),
                                    startInclusive,
                                    endExclusive,
                                    sensor.getTimezone()
                            );
        }

        Map<Short, Double> valuesByHour =
                databaseResults.stream()
                        .collect(Collectors.toMap(
                                HourlySensorStatisticProjection::getHour,
                                result -> toDisplayValue(
                                        sensor.getType(),
                                        result.getValue(),
                                        temperatureUnit
                                )
                        ));

        List<SensorHourlyStatisticDTO> result =
                new ArrayList<>(24);

        for (short hour = 0; hour < 24; hour++) {
            Double value = valuesByHour.get(hour);

            if (value == null
                    && sensor.getType() == SensorType.MOTION) {
                value = 0.0;
            }

            result.add(
                    new SensorHourlyStatisticDTO(hour, value)
            );
        }

        return result;
    }

    private SensorReadingViewDTO toViewDTO(
            SensorReading reading,
            Sensor sensor,
            TemperatureUnit temperatureUnit
    ) {
        return new SensorReadingViewDTO(
                sensor.getType(),
                sensor.getHomeLocation(),
                toDisplayValue(
                        sensor.getType(),
                        reading.getNumericValue(),
                        temperatureUnit
                ),
                reading.getBooleanValue(),
                getDisplayUnitSymbol(
                        sensor.getType(),
                        temperatureUnit
                ),
                reading.getRecordedAt().atZone(
                        ZoneId.of(sensor.getTimezone())
                )
        );
    }

    private Double toDisplayValue(
            SensorType sensorType,
            Double canonicalValue,
            TemperatureUnit temperatureUnit
    ) {
        if (canonicalValue == null) {
            return null;
        }

        if (sensorType == SensorType.TEMPERATURE) {
            return temperatureUnitConverter.convertFromCelsius(
                    canonicalValue,
                    temperatureUnit
            );
        }

        return canonicalValue;
    }

    private String getDisplayUnitSymbol(
            SensorType sensorType,
            TemperatureUnit temperatureUnit
    ) {
        return switch (sensorType) {
            case TEMPERATURE ->
                    temperatureUnitConverter.getSymbol(
                            temperatureUnit
                    );

            case HUMIDITY -> "% RH";
            case MOTION -> "";
        };
    }

    private MeasurementUnit getCanonicalUnit(
            SensorType sensorType
    ) {
        return switch (sensorType) {
            case TEMPERATURE -> MeasurementUnit.C;
            case HUMIDITY -> MeasurementUnit.PERCENT;

            case MOTION -> throw new IllegalArgumentException(
                    "Motion sensors do not have a numeric unit"
            );
        };
    }

    private Sensor getOwnedSensor(
            Long sensorId,
            Long ownerId
    ) {
        return sensorRepository
                .findByIdAndOwnerId(sensorId, ownerId)
                .orElseThrow(() ->
                        new NoSuchElementException(
                                "Sensor not found"
                        )
                );
    }
}