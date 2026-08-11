package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface DailySensorSummaryRepository extends JpaRepository<DailySensorSummary,Long> {

    Optional<DailySensorSummary> findBySensorIdAndBucketStart(Long sensorId, Instant bucketStart);


    boolean existsBySensorIdAndBucketEndLessThanEqual(Long sensorId, Instant bucketEnd);



    @Modifying(clearAutomatically = true,flushAutomatically = true)
    @NativeQuery("""
            WITH deletion_candidates AS (
                SELECT summary.id
                FROM daily_sensor_summaries summary
                WHERE summary.sensor_id = :sensorId
                  AND summary.bucket_end <= :retentionBoundaryInclusive
                ORDER BY summary.bucket_end,summary.id
                LIMIT :batchSize
                FOR UPDATE OF summary SKIP LOCKED
            )
            DELETE FROM daily_sensor_summaries summary
            USING deletion_candidates candidate
            WHERE summary.id = candidate.id
            """)
    int deleteOldestRetentionBatch(
            @Param("sensorId") Long sensorId,
            @Param("retentionBoundaryInclusive") Instant retentionBoundaryInclusive,
            @Param("batchSize") int batchSize
    );

}