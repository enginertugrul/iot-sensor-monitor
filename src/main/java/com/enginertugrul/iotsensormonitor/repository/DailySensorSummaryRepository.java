package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.DailySensorSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface DailySensorSummaryRepository extends JpaRepository<DailySensorSummary,Long> {

    Optional<DailySensorSummary> findBySensorIdAndBucketStart(Long sensorId, Instant bucketStart);

}