package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface HourlySensorSummaryRepository extends JpaRepository<HourlySensorSummary,Long> {

    Optional<HourlySensorSummary> findBySensorIdAndBucketStart(Long sensorId, Instant bucketStart);

    @Query("""
            SELECT summary
            FROM HourlySensorSummary summary
            WHERE summary.sensor.id = :sensorId
              AND summary.bucketStart >= :startInclusive
              AND summary.bucketStart < :endExclusive
            ORDER BY summary.bucketStart
            """)
    List<HourlySensorSummary> findForDailyRollup(
            @Param("sensorId") Long sensorId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );



    boolean existsBySensorIdAndBucketEndLessThanEqual(Long sensorId, Instant bucketEnd);



    @NativeQuery("""
            SELECT EXISTS (
                SELECT 1
                FROM hourly_sensor_summaries summary
                JOIN sensor_rollup_checkpoints daily_checkpoint
                    ON daily_checkpoint.sensor_id = summary.sensor_id
                   AND daily_checkpoint.stage = 'HOURLY_TO_DAILY'
                WHERE summary.sensor_id = :sensorId
                  AND summary.bucket_end <= :retentionBoundaryInclusive
                  AND summary.bucket_end <= (
                      date_trunc(
                              'hour',
                              daily_checkpoint.covered_until AT TIME ZONE 'UTC'
                      ) AT TIME ZONE 'UTC'
                  )
            )
            """)
    boolean existsEligibleForRetentionPurge(
            @Param("sensorId") Long sensorId,
            @Param("retentionBoundaryInclusive") Instant retentionBoundaryInclusive
    );



    @Modifying(clearAutomatically = true,flushAutomatically = true)
    @NativeQuery("""
            WITH deletion_candidates AS (
                SELECT summary.id
                FROM hourly_sensor_summaries summary
                JOIN sensor_rollup_checkpoints daily_checkpoint
                    ON daily_checkpoint.sensor_id = summary.sensor_id
                   AND daily_checkpoint.stage = 'HOURLY_TO_DAILY'
                WHERE summary.sensor_id = :sensorId
                  AND summary.bucket_end <= :retentionBoundaryInclusive
                  AND summary.bucket_end <= (
                      date_trunc(
                              'hour',
                              daily_checkpoint.covered_until AT TIME ZONE 'UTC'
                      ) AT TIME ZONE 'UTC'
                  )
                ORDER BY summary.bucket_end,summary.id
                LIMIT :batchSize
                FOR UPDATE OF summary SKIP LOCKED
            )
            DELETE FROM hourly_sensor_summaries summary
            USING deletion_candidates candidate
            WHERE summary.id = candidate.id
            """)
    int deleteOldestEligibleRetentionBatch(
            @Param("sensorId") Long sensorId,
            @Param("retentionBoundaryInclusive") Instant retentionBoundaryInclusive,
            @Param("batchSize") int batchSize
    );


}