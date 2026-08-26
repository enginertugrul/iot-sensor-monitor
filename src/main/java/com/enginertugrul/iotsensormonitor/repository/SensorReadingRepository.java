package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {





    List<SensorReading> findTop10BySensorIdAndSensorOwnerIdOrderByRecordedAtDesc(Long sensorId, Long ownerId);



    @Query("""
            SELECT reading
            FROM SensorReading reading
            WHERE reading.sensor.id = :sensorId
              AND reading.recordedAt >= :startInclusive
              AND reading.recordedAt < :endExclusive
            ORDER BY reading.recordedAt,reading.id
            """)
    Slice<SensorReading> findForStatisticsRange(
            @Param("sensorId") Long sensorId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            Pageable pageable
    );






    @NativeQuery("""
        SELECT
            COUNT(*) AS "sourceSampleCount",
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




    boolean existsBySensorIdAndRecordedAtBefore(Long sensorId, Instant recordedAt);



    @NativeQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM sensor_readings reading
                JOIN sensor_rollup_checkpoints raw_checkpoint
                    ON raw_checkpoint.sensor_id = reading.sensor_id
                   AND raw_checkpoint.stage = 'RAW_TO_HOURLY'
                JOIN sensor_rollup_checkpoints daily_checkpoint
                    ON daily_checkpoint.sensor_id = reading.sensor_id
                   AND daily_checkpoint.stage = 'HOURLY_TO_DAILY'
                WHERE reading.sensor_id = :sensorId
                  AND reading.recorded_at < :retentionBoundaryExclusive
                  AND reading.recorded_at < raw_checkpoint.covered_until
                  AND reading.recorded_at < (
                      date_trunc(
                              'hour',
                              daily_checkpoint.covered_until AT TIME ZONE 'UTC'
                      ) AT TIME ZONE 'UTC'
                  )
            )
            """)
    boolean existsEligibleForRetentionPurge(
            @Param("sensorId") Long sensorId,
            @Param("retentionBoundaryExclusive") Instant retentionBoundaryExclusive
    );



    @Modifying(clearAutomatically = true,flushAutomatically = true)
    @NativeQuery("""
            WITH deletion_candidates AS (
                SELECT reading.id
                FROM sensor_readings reading
                JOIN sensor_rollup_checkpoints hourly_checkpoint
                    ON hourly_checkpoint.sensor_id = reading.sensor_id
                   AND hourly_checkpoint.stage = 'RAW_TO_HOURLY'
                JOIN sensor_rollup_checkpoints daily_checkpoint
                    ON daily_checkpoint.sensor_id = reading.sensor_id
                   AND daily_checkpoint.stage = 'HOURLY_TO_DAILY'
                WHERE reading.sensor_id = :sensorId
                  AND reading.recorded_at < :retentionBoundaryExclusive
                  AND reading.recorded_at < hourly_checkpoint.covered_until
                  AND reading.recorded_at < (
                      date_trunc(
                              'hour',
                              daily_checkpoint.covered_until AT TIME ZONE 'UTC'
                      ) AT TIME ZONE 'UTC'
                  )
                ORDER BY reading.recorded_at,reading.id
                LIMIT :batchSize
                FOR UPDATE OF reading SKIP LOCKED
            )
            DELETE FROM sensor_readings reading
            USING deletion_candidates candidate
            WHERE reading.id = candidate.id
            """)
    int deleteOldestEligibleRetentionBatch(
            @Param("sensorId") Long sensorId,
            @Param("retentionBoundaryExclusive") Instant retentionBoundaryExclusive,
            @Param("batchSize") int batchSize
    );



}