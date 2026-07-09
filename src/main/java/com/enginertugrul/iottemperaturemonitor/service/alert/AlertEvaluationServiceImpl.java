package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.repository.AlertRuleRepository;
import org.slf4j.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class AlertEvaluationServiceImpl implements AlertEvaluationService {

    private static final String TEMPERATURE_CANONICAL_UNIT = "C";

    private final Logger logger = LoggerFactory.getLogger(AlertEvaluationServiceImpl.class);
    private final AlertRuleRepository alertRuleRepository;
    private final ApplicationEventPublisher eventPublisher;

    public AlertEvaluationServiceImpl(
            AlertRuleRepository alertRuleRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.alertRuleRepository = alertRuleRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public void evaluateTemperatureReading(Sensor sensor, Double celsiusValue, Instant recordedAt) {
        Objects.requireNonNull(sensor, "sensor must not be null");
        Objects.requireNonNull(recordedAt, "recordedAt must not be null");
        Objects.requireNonNull(celsiusValue, "celsiusValue must not be null");

        alertRuleRepository.findBySensorIdAndEnabledTrue(sensor.getId())
                .stream()
                .filter(this::isTemperatureThresholdRule)
                .filter(rule -> rule.getComparisonOperator().matches(celsiusValue, rule.getThresholdValue()))
                .forEach(rule -> triggerIfCooldownAllows(rule, celsiusValue, recordedAt));
    }

    private boolean isTemperatureThresholdRule(AlertRule rule) {
        return rule.getRuleType() == AlertRuleType.NUMERIC_THRESHOLD
                && rule.getSensor().getType() == SensorType.TEMPERATURE
                && TEMPERATURE_CANONICAL_UNIT.equals(rule.getThresholdUnit());
    }

    private void triggerIfCooldownAllows(AlertRule rule, Double celsiusValue, Instant recordedAt) {
        if (!rule.canTriggerAt(recordedAt)) {
            logger.info("Alert suppressed by cooldown. alertRuleId={}", rule.getId());
            return;
        }

        rule.markTriggered(recordedAt);
        eventPublisher.publishEvent(AlertTriggeredEvent.from(rule, celsiusValue, recordedAt));

        logger.info("Alert triggered. alertRuleId={}, sensorId={}", rule.getId(), rule.getSensor().getId());
    }
}