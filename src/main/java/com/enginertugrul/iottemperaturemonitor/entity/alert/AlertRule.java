package com.enginertugrul.iottemperaturemonitor.entity.alert;

import com.enginertugrul.iottemperaturemonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iottemperaturemonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
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
    @Column(name = "rule_type",  nullable = false, length = 40)
    private AlertRuleType ruleType;

    @Enumerated(EnumType.STRING)
    @Column(name = "comparison_operator", length = 30)
    private ComparisonOperator comparisonOperator;

    @Column(name = "threshold_value")
    private Double thresholdValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "threshold_unit", length = 25)
    private MeasurementUnit thresholdUnit;

    @Enumerated(EnumType.STRING)
    @Column( name = "event_type" , length = 40)
    private AlertEventType eventType;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name= "created_at", nullable= false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable= false)
    private Instant updatedAt;

    @Column(name= "cooldown_minutes" , nullable = false)
    private Integer cooldownMinutes = AlertCooldownPolicy.DEFAULT_MINUTES;

    @Column(name= "last_triggered_at")
    private Instant lastTriggeredAt;


    private AlertRule(Sensor sensor,int cooldownMinutes) {

        this.sensor = Objects.requireNonNull(sensor, "sensor must not be null");
        this.owner = Objects.requireNonNull(sensor.getOwner(), "sensor owner must not be null");
        this.cooldownMinutes = cooldownMinutes;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }


    private AlertRule(Sensor sensor, AlertEventType eventType, int cooldownMinutes) {

        this(sensor, cooldownMinutes);
        this.ruleType = AlertRuleType.EVENT_DETECTED;
        this.eventType = Objects.requireNonNull(eventType, "eventType must not be null");
    }


    private AlertRule(Sensor sensor, ComparisonOperator comparisonOperator, double thresholdValue, MeasurementUnit thresholdUnit, int cooldownMinutes) {

        this(sensor, cooldownMinutes);
        this.ruleType = AlertRuleType.NUMERIC_THRESHOLD;
        this.comparisonOperator = Objects.requireNonNull(comparisonOperator, "comparisonOperator must not be null");
        this.thresholdValue = thresholdValue;
        this.thresholdUnit = Objects.requireNonNull(thresholdUnit, "thresholdUnit must not be null");
    }




    public static AlertRule numericThreshold(Sensor sensor, ComparisonOperator comparisonOperator, Double canonicalThresholdValue, Integer cooldownMinutes) {

        Sensor requiredSensor = Objects.requireNonNull(sensor,"sensor must not be null");

        double validThreshold = SensorMeasurementPolicy.requireValidNumericValue(
                requiredSensor.getType(),
                                canonicalThresholdValue,
                                "canonicalThresholdValue");

        return new AlertRule(
                requiredSensor,
                Objects.requireNonNull(comparisonOperator, "comparisonOperator must not be null"),
                validThreshold,
                SensorMeasurementPolicy.requireCanonicalUnit(requiredSensor.getType()),
                AlertCooldownPolicy.requireValid(cooldownMinutes)
        );
    }


    public static AlertRule motionDetected(Sensor sensor, Integer cooldownMinutes) {

        Sensor requiredSensor = Objects.requireNonNull(sensor,"sensor must not be null");

        if(requiredSensor.getType() != SensorType.MOTION) {
            throw new IllegalArgumentException("Motion rules require a motion sensor type");
        }

        return new AlertRule( requiredSensor,
                AlertEventType.MOTION_DETECTED,
                AlertCooldownPolicy.requireValid(cooldownMinutes)
        );

    }



    public boolean isTriggeredBy(SensorReading reading) {

        SensorReading requiredReading = Objects.requireNonNull(reading,"sensorReading must not be null");

        if(!belongsToSameSensor(requiredReading)) {
            return false;
        }

        return switch (ruleType) {
            case NUMERIC_THRESHOLD -> matchesNumericReading(requiredReading);
            case EVENT_DETECTED ->  matchesEventReading(requiredReading);
        };

    }




    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }




    public boolean isCooldownActiveAt(Instant checkedAt) {
        Objects.requireNonNull(checkedAt, "checkedAt must not be null");

        if (lastTriggeredAt == null) {
            return false;
        }

        Instant cooldownEndsAt = lastTriggeredAt.plus(Duration.ofMinutes(cooldownMinutes));
        return cooldownEndsAt.isAfter(checkedAt);
    }




    public boolean canTriggerAt(Instant checkedAt) {
        return enabled && !isCooldownActiveAt(checkedAt);
    }





    public void markTriggered(Instant triggeredAt) {
        this.lastTriggeredAt = Objects.requireNonNull(triggeredAt, "triggeredAt must not be null");
        this.updatedAt = Instant.now();
    }



    private boolean belongsToSameSensor(SensorReading reading) {
        Sensor readingSensor = Objects.requireNonNull(reading.getSensor(), "reading sensor must not be null");
        if (sensor == readingSensor) {
            return true;
        }

        Long ruleSensorId = sensor.getId();
        Long readingSensorId = readingSensor.getId();

        return ruleSensorId.equals(readingSensorId);
    }






    private boolean matchesNumericReading(SensorReading reading) {
        return reading.getNumericValue() != null
                && reading.getBooleanValue() == null
                && reading.getUnit() == thresholdUnit
                && comparisonOperator.matches(
                reading.getNumericValue(),
                thresholdValue
        );
    }






    private boolean matchesEventReading(SensorReading reading) {

        return switch (eventType) {
            case MOTION_DETECTED ->
                    reading.getNumericValue() == null
                            && reading.getUnit() == null
                            && Boolean.TRUE.equals(
                            reading.getBooleanValue()
                    );
        };
    }




    @PrePersist
    void prePersist() {
        Instant now = Instant.now();

        validateRuleShape();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        validateRuleShape();
        updatedAt = Instant.now();
    }



    private void validateRuleShape() {

        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(sensor, "sensor must not be null");

        requireOwnerMatchesSensor();

        cooldownMinutes = AlertCooldownPolicy.requireValid(cooldownMinutes);

        switch (Objects.requireNonNull(ruleType,"ruleType must not be null")) {
            case NUMERIC_THRESHOLD -> requireNumericThresholdShape();
            case EVENT_DETECTED -> requireEventDetectedShape();
        }
    }







    private void requireNumericThresholdShape() {

        Objects.requireNonNull(comparisonOperator,"comparisonOperator must not be null for numeric thresholds");

        thresholdValue = SensorMeasurementPolicy.requireValidNumericValue(sensor.getType(),
                thresholdValue,
                "thresholdValue");

        MeasurementUnit expectedUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensor.getType());

        if(thresholdUnit != expectedUnit) {
            throw new IllegalArgumentException("Threshold unit does not match sensor type");
        }

        if (eventType != null) {
            throw new IllegalArgumentException("eventType must be null for numeric threshold rules");
        }
    }





    private void requireEventDetectedShape() {

        if (sensor.getType() != SensorType.MOTION) {
            throw new IllegalArgumentException("Event rules require a motion sensor");
        }

        if (eventType != AlertEventType.MOTION_DETECTED) {
            throw new IllegalArgumentException("Motion rules require MOTION_DETECTED");
        }

        if (comparisonOperator != null || thresholdValue != null || thresholdUnit != null) {
            throw new IllegalArgumentException("Threshold fields must be null for event rules");
        }

    }





    private void requireOwnerMatchesSensor() {
        AppUser sensorOwner = Objects.requireNonNull(sensor.getOwner(), "sensor owner must not be null");

        if (owner == sensorOwner) {
            return;
        }

        Long ownerId = owner.getId();
        Long sensorOwnerId = sensorOwner.getId();

        if (ownerId == null || !ownerId.equals(sensorOwnerId)) {
            throw new IllegalArgumentException("Alert rule owner must own the sensor");
        }
    }



}
