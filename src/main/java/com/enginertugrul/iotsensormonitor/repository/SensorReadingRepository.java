package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {





    List<SensorReading> findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDesc(Long sensorId, Long ownerId);



    @NativeQuery("""
            SELECT
                DATE(sr.recorded_at AT TIME ZONE :timezone) AS date,
                AVG(sr.numeric_value) AS value
            FROM sensor_readings sr
            WHERE sr.sensor_id = :sensorId
              AND sr.unit = :unit
              AND sr.recorded_at >= :startInclusive
              AND sr.recorded_at < :endExclusive
            GROUP BY date
            ORDER BY date
            """)
    List<DailySensorStatisticProjection> findDailyNumericStatistics(
            @Param("sensorId") Long sensorId,
            @Param("unit") String unit,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("timezone") String timezone
    );





    @NativeQuery("""
            SELECT
                CAST(
                    EXTRACT(
                        HOUR FROM sr.recorded_at AT TIME ZONE :timezone
                    ) AS SMALLINT
                ) AS hour,
                AVG(sr.numeric_value) AS value
            FROM sensor_readings sr
            WHERE sr.sensor_id = :sensorId
              AND sr.unit = :unit
              AND sr.recorded_at >= :startInclusive
              AND sr.recorded_at < :endExclusive
            GROUP BY hour
            ORDER BY hour
            """)
    List<HourlySensorStatisticProjection> findHourlyNumericStatistics(
            @Param("sensorId") Long sensorId,
            @Param("unit") String unit,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("timezone") String timezone
    );





    @NativeQuery("""
            SELECT
                DATE(sr.recorded_at AT TIME ZONE :timezone) AS date,
                CAST(COUNT(*) AS DOUBLE PRECISION) AS value
            FROM sensor_readings sr
            WHERE sr.sensor_id = :sensorId
              AND sr.boolean_value IS TRUE
              AND sr.recorded_at >= :startInclusive
              AND sr.recorded_at < :endExclusive
            GROUP BY date
            ORDER BY date
            """)
    List<DailySensorStatisticProjection>
    findDailyMotionDetectionCounts(
            @Param("sensorId") Long sensorId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("timezone") String timezone
    );





    @NativeQuery("""
            SELECT
                CAST(
                    EXTRACT(
                        HOUR FROM sr.recorded_at AT TIME ZONE :timezone
                    ) AS SMALLINT
                ) AS hour,
                CAST(COUNT(*) AS DOUBLE PRECISION) AS value
            FROM sensor_readings sr
            WHERE sr.sensor_id = :sensorId
              AND sr.boolean_value IS TRUE
              AND sr.recorded_at >= :startInclusive
              AND sr.recorded_at < :endExclusive
            GROUP BY hour
            ORDER BY hour
            """)
    List<HourlySensorStatisticProjection>
    findHourlyMotionDetectionCounts(
            @Param("sensorId") Long sensorId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("timezone") String timezone
    );




    @Query("""
            SELECT MIN(reading.recordedAt)
            FROM SensorReading reading
            WHERE reading.sensor.id = :sensorId
            """)
    Optional<Instant> findEarliestRecordedAt(@Param("sensorId") Long sensorId);



    @NativeQuery("""
            SELECT
                COUNT(*) AS "sourceSampleCount",
                COUNT(sr.numeric_value) AS "numericSampleCount",
                COUNT(sr.boolean_value) AS "booleanSampleCount",
                COUNT(sr.unit) AS "unitSampleCount",
                MIN(sr.unit) AS "minimumUnit",
                MAX(sr.unit) AS "maximumUnit",
                SUM(CAST(sr.numeric_value AS NUMERIC)) AS "numericSum",
                MIN(sr.numeric_value) AS "numericMinimum",
                MAX(sr.numeric_value) AS "numericMaximum",
                COUNT(*) FILTER (
                    WHERE sr.boolean_value IS TRUE
                ) AS "trueSampleCount"
            FROM sensor_readings sr
            WHERE sr.sensor_id = :sensorId
              AND sr.recorded_at >= :startInclusive
              AND sr.recorded_at < :endExclusive
            """)
    RawSensorReadingAggregateProjection aggregateForSummaryRange(
            @Param("sensorId") Long sensorId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );





}