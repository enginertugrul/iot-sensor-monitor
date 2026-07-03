package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorDailyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.dto.reading.SensorHourlyAverageDTO;
import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    @Modifying
    @Query("DELETE FROM SensorReading r WHERE r.recordedAt < :cutoffTimestamp")
    void deleteOlderThan(Instant cutoffTimestamp);

    List<SensorReading> findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDesc(Long sensorId, Long ownerId);

    @NativeQuery("""
        SELECT DATE(sr.recorded_at AT TIME ZONE :timezone) AS date,
               AVG(sr.numeric_value)
        FROM sensor_readings sr
        WHERE sr.sensor_id = :sensorId
          AND sr.numeric_value IS NOT NULL
          AND sr.recorded_at >= :untilDate
        GROUP BY date
        ORDER BY date
        """)
    List<SensorDailyAverageDTO> findDailyAverageValuesSince(Long sensorId, Instant untilDate, String timezone);

    @NativeQuery("""
        SELECT CAST(EXTRACT(HOUR FROM sr.recorded_at AT TIME ZONE :timezone) AS smallint) AS hour,
               AVG(sr.numeric_value)
        FROM sensor_readings sr
        WHERE sr.sensor_id = :sensorId
          AND sr.numeric_value IS NOT NULL
          AND sr.recorded_at >= :startOfDay
          AND sr.recorded_at < :endOfDay
        GROUP BY hour
        ORDER BY hour
        """)
    List<SensorHourlyAverageDTO> findHourlyAverageValuesForDate(
            Long sensorId,
            Instant startOfDay,
            Instant endOfDay,
            String timezone
    );
}