package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleForm;
import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRule;
import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.Sensor;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.repository.AlertRuleRepository;
import com.enginertugrul.iottemperaturemonitor.service.sensor.SensorService;
import com.enginertugrul.iottemperaturemonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;

@Service
public class AlertRuleServiceImpl implements AlertRuleService {


    private static final String TEMPERATURE_CANONICAL_UNIT = "C";
    private static final double ABSOLUTE_ZERO_CELSIUS = -273.15;

    private final AlertRuleRepository alertRuleRepository;
    private final SensorService sensorService;
    private final TemperatureUnitConverter temperatureUnitConverter;

    public AlertRuleServiceImpl(AlertRuleRepository alertRuleRepository, SensorService sensorService, TemperatureUnitConverter temperatureUnitConverter) {
        this.alertRuleRepository = alertRuleRepository;
        this.sensorService = sensorService;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }




    @Override
    @Transactional(readOnly = true)
    public List<AlertRuleListItemDTO> getAlertRulesForUser(Long ownerId, TemperatureUnit preferredTemperatureUnit) {
        return alertRuleRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId)
                .stream().map( rule -> toListItem(rule,preferredTemperatureUnit))
                .toList();
    }



    @Override
    @Transactional
    public void createTemperatureThresholdRule(Long ownerId, AlertRuleForm form, TemperatureUnit preferredTemperatureUnit) {

        Sensor sensor = sensorService.getSensorForUser(form.getSensorId(), ownerId);

        if(sensor.getType() != SensorType.TEMPERATURE) {
            throw new IllegalArgumentException("Only temperature threshold rules are supported by this form");
        }

        Double thresholdInCelsius = temperatureUnitConverter.convertToCelsius( form.getThresholdValue() , preferredTemperatureUnit);

        if(thresholdInCelsius < ABSOLUTE_ZERO_CELSIUS) {
            throw new IllegalArgumentException("Temperature threshold cannot be below absolute zero");
        }

        AlertRule alertRule = AlertRule.numericThreshold(sensor.getOwner(),
                sensor,
                form.getComparisonOperator(),
                thresholdInCelsius,
                TEMPERATURE_CANONICAL_UNIT,
                form.getCooldownMinutes());

        alertRuleRepository.save(alertRule);


    }



    @Override
    @Transactional
    public void setAlertRuleEnabled(Long ownerId, Long id, Boolean enabled) {
        AlertRule alertRule = getOwnedAlertRule(id, ownerId);
        if (enabled) {
            alertRule.enable();
        } else {
            alertRule.disable();
        }

    }

    @Override
    @Transactional
    public void deleteAlertRule(Long ownerId, Long alertRuleId) {
        AlertRule alertRule = getOwnedAlertRule(alertRuleId,ownerId);
        alertRuleRepository.delete(alertRule);
    }


    private AlertRule getOwnedAlertRule(Long alertRuleId, Long ownerId) {
        return alertRuleRepository.findByIdAndOwnerId(alertRuleId, ownerId)
                .orElseThrow( ()-> new NoSuchElementException("Alert rule not found"));
    }


    private AlertRuleListItemDTO toListItem(AlertRule rule, TemperatureUnit preferredTemperatureUnit) {
        Sensor sensor = rule.getSensor();

        Double displayThreshold = rule.getThresholdValue();
        String displayUnit = rule.getThresholdUnit();

        if (
                rule.getRuleType() == AlertRuleType.NUMERIC_THRESHOLD
                        && sensor.getType() == SensorType.TEMPERATURE
                        && TEMPERATURE_CANONICAL_UNIT.equals(rule.getThresholdUnit())
        ) {
            displayThreshold = temperatureUnitConverter.convertFromCelsius(
                    rule.getThresholdValue(),
                    preferredTemperatureUnit
            );
            displayUnit = temperatureUnitConverter.getSymbol(preferredTemperatureUnit);
        }

        return new AlertRuleListItemDTO(
                rule.getId(),
                sensor.getId(),
                sensor.getName(),
                sensor.getHomeLocation(),
                sensor.getType(),
                rule.getRuleType(),
                rule.getComparisonOperator(),
                rule.getEventType(),
                displayThreshold,
                displayUnit,
                rule.getCooldownMinutes(),
                rule.isEnabled()
        );
    }













}
