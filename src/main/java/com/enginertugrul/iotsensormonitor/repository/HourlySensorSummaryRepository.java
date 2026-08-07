package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.HourlySensorSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface HourlySensorSummaryRepository extends JpaRepository<HourlySensorSummary,Long> {

    Optional<HourlySensorSummary> findBySensorIdAndBucketStart(Long sensorId, Instant bucketStart);


}