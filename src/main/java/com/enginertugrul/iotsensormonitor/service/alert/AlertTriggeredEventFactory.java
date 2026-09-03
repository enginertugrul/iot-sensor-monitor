package com.enginertugrul.iotsensormonitor.service.alert;


import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.reading.SensorReading;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Objects;





@Component
public class AlertTriggeredEventFactory {


    public AlertTriggeredEvent from(AlertRule rule , SensorReading reading) {

        Objects.requireNonNull(rule, "rule must not be null");

        Objects.requireNonNull(reading, "reading must not be null");

        Sensor sensor = rule.getSensor();
        AppUser owner =  rule.getOwner();

        AlertTriggeredEvent.Context context = new AlertTriggeredEvent.Context(
                rule.getId(),
                recipientSnapShot(owner),
                sensorSnapshot(sensor),
                reading.getRecordedAt(),
                rule.getCooldownMinutes());

        return new AlertTriggeredEvent(context,triggerFrom(rule,reading));


    }


    private AlertTriggeredEvent.SensorSnapshot sensorSnapshot(Sensor sensor) {

        return new  AlertTriggeredEvent.SensorSnapshot(sensor.getId(), sensor.getType(),
                sensor.getName(),sensor.getInstallationLocation(),
                sensor.getCity(),sensor.getDistrict());
    }




    private AlertTriggeredEvent.RecipientSnapshot recipientSnapShot(AppUser owner) {

        return new AlertTriggeredEvent.RecipientSnapshot(owner.getEmail(),owner.getPreferredLanguage(),
                owner.getPreferredTemperatureUnit(), ZoneId.of(owner.getPreferredTimezone()));
    }




    private AlertTriggeredEvent.Trigger triggerFrom(AlertRule rule, SensorReading reading) {

        return switch (rule.getRuleType()) {

            case NUMERIC_THRESHOLD ->
                    new AlertTriggeredEvent.NumericThresholdTrigger(rule.getComparisonOperator(),
                            reading.getNumericValue(),
                            rule.getThresholdValue(),
                            reading.getUnit());

            case EVENT_DETECTED ->
                    switch (rule.getEventType()) {
                        case MOTION_DETECTED ->
                            new AlertTriggeredEvent.MotionDetectedTrigger();
            };

        };

    }



}
