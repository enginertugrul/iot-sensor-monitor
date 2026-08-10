package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import org.springframework.data.jpa.repository.JpaRepository;
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



}