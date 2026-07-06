package com.enginertugrul.iottemperaturemonitor.entity.alert;

import com.enginertugrul.iottemperaturemonitor.entity.DomainChecks;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Getter
@Entity
@Table(name = "alert_rules")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AlertRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id" , nullable = false)
    private AppUser owner;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id" , nullable = false)
    private Sensor sensor;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_rule_type",  nullable = false, length = 40)
    private AlertRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_operator", length = 30)
    private ComparisonOperator comparisonOperator;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Column(name = "threshold_unit", length = 25)
    private String thresholdUnit;

    @Enumerated(EnumType.STRING)
    @Column( name = "alert_event_type" , length = 40)
    private AlertEventType eventType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name= "created_at", nullable= false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable= false)
    private Instant updatedAt;


    private AlertRule(
            AppUser owner,
            Sensor sensor,
            AlertRuleType ruleType,
            ComparisonOperator comparisonOperator,
            Double thresholdValue,
            String thresholdUnit,
            AlertEventType eventType
    ) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.sensor = Objects.requireNonNull(sensor, "sensor must not be null");
        this.ruleType = Objects.requireNonNull(ruleType, "ruleType must not be null");
        this.comparisonOperator = comparisonOperator;
        this.thresholdValue = thresholdValue;
        this.thresholdUnit = thresholdUnit;
        this.eventType = eventType;

        validateRuleShape();

        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static AlertRule numericThreshold(
            AppUser owner,
            Sensor sensor,
            ComparisonOperator comparisonOperator,
            Double thresholdValue,
            String thresholdUnit
    ) {
        return new AlertRule(
                owner,
                sensor,
                AlertRuleType.NUMERIC_THRESHOLD,
                Objects.requireNonNull(comparisonOperator, "comparisonOperator must not be null"),
                DomainChecks.requireFiniteDouble(thresholdValue, "thresholdValue"),
                DomainChecks.requireText(thresholdUnit, "thresholdUnit"),
                null
        );
    }

    public static AlertRule eventDetected(
            AppUser owner,
            Sensor sensor,
            AlertEventType eventType
    ) {
        return new AlertRule(
                owner,
                sensor,
                AlertRuleType.EVENT_DETECTED,
                null,
                null,
                null,
                Objects.requireNonNull(eventType, "eventType must not be null")
        );
    }

    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    private void validateRuleShape() {
        if (ruleType == AlertRuleType.NUMERIC_THRESHOLD) {
            requireNumericThresholdShape();
            return;
        }

        if (ruleType == AlertRuleType.EVENT_DETECTED) {
            requireEventDetectedShape();
        }
    }

    private void requireNumericThresholdShape() {
        if (comparisonOperator == null) {
            throw new IllegalArgumentException("comparisonOperator must not be null for numeric threshold rules");
        }

        DomainChecks.requireFiniteDouble(thresholdValue, "thresholdValue");
        thresholdUnit = DomainChecks.requireText(thresholdUnit, "thresholdUnit");

        if (eventType != null) {
            throw new IllegalArgumentException("eventType must be null for numeric threshold rules");
        }
    }

    private void requireEventDetectedShape() {
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null for event detected rules");
        }

        if (comparisonOperator != null || thresholdValue != null || thresholdUnit != null) {
            throw new IllegalArgumentException("threshold fields must be null for event detected rules");
        }
    }



}
