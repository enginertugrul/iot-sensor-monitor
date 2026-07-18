package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iottemperaturemonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

public record AlertTriggeredEvent(Context context , Trigger trigger) {

    public AlertTriggeredEvent {
        Objects.requireNonNull(context, "context must not be null");
        Objects.requireNonNull(trigger, "trigger must not be null");
    }


    public record Context(long alertRuleId, RecipientSnapshot recipient, SensorSnapshot sensor, Instant recordedAt , int cooldownMinutes){
        public Context{
            Objects.requireNonNull(recipient, "recipient must not be null");
            Objects.requireNonNull(sensor, "sensor must not be null");
            Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        }
    }

    public record RecipientSnapshot(String email, PreferredLanguage language, TemperatureUnit preferredTemperatureUnit, ZoneId timezone){

        public RecipientSnapshot {

            Objects.requireNonNull(email, "email must not be null");
            Objects.requireNonNull(language, "language must not be null");
            Objects.requireNonNull(preferredTemperatureUnit, "preferredTemperatureUnit must not be null");
            Objects.requireNonNull(timezone, "timezone must not be null");

        }

    }


    public record SensorSnapshot(long id, SensorType type, String name, String homeLocation, String city,
                                 String district) {

        public SensorSnapshot{

            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(name, "name must not be null");
            Objects.requireNonNull(homeLocation, "homeLocation must not be null");
            Objects.requireNonNull(city, "city must not be null");
            Objects.requireNonNull(district, "district must not be null");
        }

    }


    public sealed interface Trigger permits NumericThresholdTrigger, MotionDetectedTrigger {
    }


    public record NumericThresholdTrigger(ComparisonOperator comparisonOperator, double readingValue, double thresholdValue, MeasurementUnit unit) implements Trigger {

        public NumericThresholdTrigger{

            Objects.requireNonNull(comparisonOperator, "comparisonOperator must not be null");
            Objects.requireNonNull(unit, "unit must not be null");

        }

    }


    public record MotionDetectedTrigger() implements Trigger{
    }













}
