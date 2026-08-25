package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
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
public interface DailySensorSummaryRepository extends JpaRepository<DailySensorSummary,Long> {




    Optional<DailySensorSummary> findBySensorIdAndBucketStart(Long sensorId, Instant bucketStart);




    @Query("""
        SELECT summary
        FROM DailySensorSummary summary
        WHERE summary.sensor.id = :sensorId
          AND summary.timeZoneId = :timeZoneId
          AND summary.bucketEnd > :retentionBoundary
          AND summary.bucketStart < :endExclusive
          AND summary.bucketEnd > :startInclusive
        ORDER BY summary.bucketStart,summary.id
        """)
    List<DailySensorSummary> findForStatisticsRange(
            @Param("sensorId") Long sensorId,
            @Param("timeZoneId") String timeZoneId,
            @Param("retentionBoundary") Instant retentionBoundary,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive
    );


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