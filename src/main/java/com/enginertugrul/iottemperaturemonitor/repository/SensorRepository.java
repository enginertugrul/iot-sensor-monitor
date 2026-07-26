package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SensorRepository extends JpaRepository<Sensor, Long> {

    List<Sensor> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);

    Optional<Sensor> findByIdAndOwnerId(Long id, Long ownerId);

    boolean existsByOwnerIdAndNameIgnoreCase(Long ownerId, String name);

    boolean existsByOwnerIdAndNameIgnoreCaseAndIdNot(Long ownerId,String name,Long sensorId);

    Optional<Sensor> findByIngestionTokenHash(String ingestionTokenHash);
}