package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import com.enginertugrul.iottemperaturemonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.time.Instant;

public record AlertTriggeredEvent(
        String recipientEmail,
        PreferredLanguage preferredLanguage,
        TemperatureUnit preferredTemperatureUnit,
        String preferredTimezone,
        Long alertRuleId,
        Long sensorId,
        String sensorName,
        String sensorHomeLocation,
        String sensorCity,
        String sensorDistrict,
        ComparisonOperator comparisonOperator,
        Double readingValueCelsius,
        Double thresholdValueCelsius,
        Instant recordedAt,
        Integer cooldownMinutes
) {

    public static AlertTriggeredEvent from(AlertRule rule, Double readingValueCelsius, Instant recordedAt) {
        AppUser owner = rule.getOwner();
        Sensor sensor = rule.getSensor();

        return new AlertTriggeredEvent(
                owner.getEmail(),
                owner.getPreferredLanguage(),
                owner.getPreferredTemperatureUnit(),
                owner.getPreferredTimezone(),
                rule.getId(),
                sensor.getId(),
                sensor.getName(),
                sensor.getHomeLocation(),
                sensor.getCity(),
                sensor.getDistrict(),
                rule.getComparisonOperator(),
                readingValueCelsius,
                rule.getThresholdValue(),
                recordedAt,
                rule.getCooldownMinutes()
        );
    }
}