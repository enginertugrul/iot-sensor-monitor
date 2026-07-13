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


    private static final double ABSOLUTE_ZERO_CELSIUS = -273.15;
    private static final double MIN_HUMIDITY_PERCENT = 0.0;
    private static final double MAX_HUMIDITY_PERCENT = 100.0;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", length = 20)
    private MeasurementUnit unit;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    private SensorReading(
            Sensor sensor,
            double numericValue,
            MeasurementUnit unit,
            Instant recordedAt
    ) {
        this.sensor = Objects.requireNonNull(
                sensor,
                "sensor must not be null"
        );

        this.unit = Objects.requireNonNull(
                unit,
                "unit must not be null for numeric readings"
        );

        this.recordedAt = Objects.requireNonNull(
                recordedAt,
                "recordedAt must not be null"
        );

        this.numericValue = numericValue;
        this.booleanValue = null;
    }

    private SensorReading(
            Sensor sensor,
            boolean booleanValue,
            Instant recordedAt
    ) {
        this.sensor = Objects.requireNonNull(
                sensor,
                "sensor must not be null"
        );

        this.recordedAt = Objects.requireNonNull(
                recordedAt,
                "recordedAt must not be null"
        );

        this.numericValue = null;
        this.booleanValue = booleanValue;
        this.unit = null;
    }


    public static SensorReading temperature(
            Sensor sensor,
            Double celsiusValue,
            Instant recordedAt
    ) {
        requireSensorType(sensor, SensorType.TEMPERATURE);
        DomainChecks.requireFiniteDouble(celsiusValue, "celsiusValue");

        if (celsiusValue < ABSOLUTE_ZERO_CELSIUS) {
            throw new IllegalArgumentException(
                    "celsiusValue cannot be below absolute zero"
            );
        }

        return new SensorReading(
                sensor,
                celsiusValue,
                MeasurementUnit.CELSIUS,
                recordedAt
        );
    }

    public static SensorReading humidity(
            Sensor sensor,
            Double humidityPercentage,
            Instant recordedAt
    ) {
        requireSensorType(sensor, SensorType.HUMIDITY);
        DomainChecks.requireFiniteDouble(humidityPercentage, "humidityPercentage");

        if (humidityPercentage < MIN_HUMIDITY_PERCENT
                || humidityPercentage > MAX_HUMIDITY_PERCENT) {
            throw new IllegalArgumentException(
                    "humidityPercentage must be between 0 and 100"
            );
        }

        return new SensorReading(
                sensor,
                humidityPercentage,
                MeasurementUnit.PERCENT,
                recordedAt
        );
    }

    public static SensorReading motion(
            Sensor sensor,
            boolean motionDetected,
            Instant recordedAt
    ) {
        requireSensorType(sensor, SensorType.MOTION);

        return new SensorReading(
                sensor,
                motionDetected,
                recordedAt
        );
    }

    private static void requireSensorType(Sensor sensor, SensorType expectedType) {
        if (sensor == null || sensor.getType() != expectedType) {
            throw new IllegalArgumentException("sensor type must be " + expectedType);
        }
    }
}