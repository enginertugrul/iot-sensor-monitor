package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    @EntityGraph(attributePaths = {"sensor"})
    List<AlertRule> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);


    Optional<AlertRule> findByIdAndOwnerId(Long id, Long ownerId);


    @EntityGraph(attributePaths = {"owner" , "sensor"})
    List<AlertRule> findBySensorIdAndEnabledTrue(Long sensorId);


    void deleteByIdAndOwnerId(Long id, Long ownerId);
}
