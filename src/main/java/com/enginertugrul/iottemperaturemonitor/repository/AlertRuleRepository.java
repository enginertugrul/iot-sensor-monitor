package com.enginertugrul.iottemperaturemonitor.repository;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;




public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {

    @EntityGraph(attributePaths = {"sensor"})
    List<AlertRule> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);


    Optional<AlertRule> findByIdAndOwnerId(Long id, Long ownerId);



    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT rule
            FROM AlertRule rule
            WHERE rule.sensor.id = :sensorId
              AND rule.enabled = true
              AND rule.owner.emailVerifiedAt IS NOT NULL
            ORDER BY rule.id
            """)
    List<AlertRule> findEnabledForEvaluationBySensorId(
            @Param("sensorId") Long sensorId
    );

}
