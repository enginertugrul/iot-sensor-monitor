package com.enginertugrul.iottemperaturemonitor.entity.reading;

import com.enginertugrul.iottemperaturemonitor.entity.DomainChecks;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Objects;

@Getter
@Entity
@Table(name = "sensor_readings")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SensorReading {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sensor_id", nullable = false)
    private Sensor sensor;

    @Column(name = "numeric_value")
    private Double numericValue;

    @Column(name = "boolean_value")
    private Boolean booleanValue;

    @Column(name = "unit", length = 20)
    private String unit;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    private SensorReading(Sensor sensor, Double numericValue, Boolean booleanValue, String unit, Instant recordedAt) {
        this.sensor = Objects.requireNonNull(sensor, "sensor must not be null");
        this.numericValue = numericValue;
        this.booleanValue = booleanValue;
        this.unit = unit;
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");

        if ((numericValue == null && booleanValue == null) || (numericValue != null && booleanValue != null)) {
            throw new IllegalArgumentException("reading must contain exactly one value");
        }
    }

    public static SensorReading temperature(Sensor sensor, Double celsiusValue, Instant recordedAt) {

        requireSensorType(sensor, SensorType.TEMPERATURE);
        DomainChecks.requireFiniteDouble(celsiusValue,"celciusValue");

        return new SensorReading(sensor, celsiusValue, null, "C", recordedAt);
    }

    public static SensorReading humidity(Sensor sensor, Double humidityPercentage, Instant recordedAt) {

        requireSensorType(sensor, SensorType.HUMIDITY);
        DomainChecks.requireFiniteDouble(humidityPercentage,"humidityPercentage");

        return new SensorReading(sensor, humidityPercentage, null, "PERCENT", recordedAt);
    }

    public static SensorReading motion(Sensor sensor, Boolean motionDetected, Instant recordedAt) {
        requireSensorType(sensor, SensorType.MOTION);

        if (motionDetected == null) {
            throw new IllegalArgumentException("motionDetected must not be null");
        }

        return new SensorReading(sensor, null, motionDetected, null, recordedAt);
    }

    private static void requireSensorType(Sensor sensor, SensorType expectedType) {
        if (sensor == null || sensor.getType() != expectedType) {
            throw new IllegalArgumentException("sensor type must be " + expectedType);
        }
    }
}