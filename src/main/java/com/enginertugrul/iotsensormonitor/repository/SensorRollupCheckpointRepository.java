package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.reading.summary.RollupStage;
import com.enginertugrul.iotsensormonitor.entity.reading.summary.SensorRollupCheckpoint;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface SensorRollupCheckpointRepository extends JpaRepository<SensorRollupCheckpoint,Long> {



    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT checkpoint
            FROM SensorRollupCheckpoint checkpoint
            WHERE checkpoint.sensor.id = :sensorId
              AND checkpoint.stage = :stage
            """)
    Optional<SensorRollupCheckpoint> findBySensorIdAndStageForUpdate(@Param("sensorId") Long sensorId, @Param("stage") RollupStage stage);



    Optional<SensorRollupCheckpoint> findBySensorIdAndStage(Long sensorId, RollupStage stage);


    @Query("""
    SELECT MIN(checkpoint.coveredUntil)
    FROM SensorRollupCheckpoint checkpoint
    WHERE checkpoint.stage = :stage
    """)
    Optional<Instant> findOldestCoveredUntilByStage(@Param("stage") RollupStage stage);



}