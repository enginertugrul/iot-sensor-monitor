package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import com.enginertugrul.iottemperaturemonitor.entity.reading.SensorReading;
import com.enginertugrul.iottemperaturemonitor.repository.AlertRuleRepository;
import org.slf4j.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;

@Service
public class AlertEvaluationServiceImpl implements AlertEvaluationService {



    private final Logger LOGGER = LoggerFactory.getLogger(AlertEvaluationServiceImpl.class);
    private final AlertRuleRepository alertRuleRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AlertTriggeredEventFactory alertTriggeredEventFactory;

    public AlertEvaluationServiceImpl(AlertRuleRepository alertRuleRepository, ApplicationEventPublisher eventPublisher, AlertTriggeredEventFactory alertTriggeredEventFactory) {
        this.alertRuleRepository = alertRuleRepository;
        this.eventPublisher = eventPublisher;
        this.alertTriggeredEventFactory = alertTriggeredEventFactory;
    }



    @Override
    public void evaluateReading(SensorReading reading) {

        SensorReading requiredReading = Objects.requireNonNull(reading, "reading must not be null");

        Long sensorId = requiredReading.getSensor().getId();

        alertRuleRepository.findEnabledForEvaluationBySensorId(sensorId).stream()
                .filter( rule -> rule.isTriggeredBy(requiredReading))
                .forEach( rule -> triggerIfCooldownAllows(rule,requiredReading));


    }



    private void triggerIfCooldownAllows(AlertRule rule, SensorReading reading) {

        Instant recordedAt = reading.getRecordedAt();


        if (!rule.canTriggerAt(recordedAt)) {
            LOGGER.info(
                    "Alert suppressed by cooldown. "
                            + "alertRuleId={}, sensorId={}",
                    rule.getId(),
                    reading.getSensor().getId()
            );

            return;
        }

        rule.markTriggered(recordedAt);
        eventPublisher.publishEvent(alertTriggeredEventFactory.from(rule,reading));

        LOGGER.info( "Alert triggered. alertRuleId={}, " + "sensorId={}, sensorType={}",
                rule.getId(),
                reading.getSensor().getId(),
                reading.getSensor().getType()
        );

    }



}