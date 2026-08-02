package com.enginertugrul.iotsensormonitor.entity.reading;

import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
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

    @Enumerated(EnumType.STRING)
    @Column(name = "unit", length = 20)
    private MeasurementUnit unit;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;





    private SensorReading(Sensor sensor, double numericValue, MeasurementUnit unit, Instant recordedAt) {

        this.sensor = Objects.requireNonNull(sensor,"sensor must not be null");
        this.unit = Objects.requireNonNull(unit, "unit must not be null for numeric readings");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        this.numericValue = numericValue;
        this.booleanValue = null;
    }




    private SensorReading(Sensor sensor, boolean booleanValue, Instant recordedAt) {

        this.sensor = Objects.requireNonNull(sensor, "sensor must not be null");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        this.numericValue = null;
        this.booleanValue = booleanValue;
        this.unit = null;
    }






    public static SensorReading temperature(Sensor sensor, Double celsiusValue, Instant recordedAt) {

        requireSensorType(sensor, SensorType.TEMPERATURE);

        double validValue = SensorMeasurementPolicy.requireValidNumericValue(sensor.getType(),
                celsiusValue,
                "celsiusValue");

        return new SensorReading(
                sensor,
                validValue,
                SensorMeasurementPolicy.requireCanonicalUnit(sensor.getType()),
                recordedAt);
    }





    public static SensorReading humidity(Sensor sensor, Double humidityPercentage, Instant recordedAt) {

        requireSensorType(sensor, SensorType.HUMIDITY);

        double validValue = SensorMeasurementPolicy.requireValidNumericValue(sensor.getType(),
                humidityPercentage,
                "humidityPercentage");

        return new SensorReading(sensor,
                validValue,
                SensorMeasurementPolicy.requireCanonicalUnit(sensor.getType()),
                recordedAt);
    }


    public static SensorReading motion(Sensor sensor, boolean motionDetected, Instant recordedAt) {

        requireSensorType(sensor, SensorType.MOTION);

        return new SensorReading( sensor,motionDetected,recordedAt);
    }





    private static void requireSensorType(Sensor sensor, SensorType expectedType) {

        Objects.requireNonNull(sensor, "sensor must not be null");

        if (sensor.getType() != expectedType) {
            throw new IllegalArgumentException("sensor type must be " + expectedType);
        }
    }

}