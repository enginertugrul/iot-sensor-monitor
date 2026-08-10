package com.enginertugrul.iotsensormonitor.repository;

import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;



@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {



    List<Sensor> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);


    @Query("""
            SELECT sensor.id AS id,
                         sensor.type AS type,
                                      sensor.timezone AS timezone
            FROM Sensor sensor
            WHERE sensor.lastSeenAt IS NOT NULL
            ORDER BY sensor.id
            """)
    List<RollupSensorProjection> findSensorsForRollup();






    Optional<Sensor> findByIdAndOwnerId(Long id, Long ownerId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sensor
            FROM Sensor sensor
            WHERE sensor.id = :sensorId
              AND sensor.owner.id = :ownerId
            """)
    Optional<Sensor> findByIdAndOwnerIdForUpdate(@Param("sensorId") Long sensorId, @Param("ownerId") Long ownerId);


    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(Long ownerId,String name,Long sensorId);


    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT sensor
            FROM Sensor sensor
            WHERE sensor.ingestionTokenHash = :ingestionTokenHash
            """)
    Optional<Sensor> findByIngestionTokenHashForUpdate(@Param("ingestionTokenHash") String ingestionTokenHash);


}