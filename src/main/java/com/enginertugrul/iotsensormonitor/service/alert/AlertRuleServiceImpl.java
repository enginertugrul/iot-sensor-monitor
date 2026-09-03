package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.alert.MotionEventAlertRuleForm;
import com.enginertugrul.iotsensormonitor.dto.alert.NumericThresholdAlertRuleForm;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertRule;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iotsensormonitor.entity.measurement.SensorMeasurementPolicy;
import com.enginertugrul.iotsensormonitor.entity.reading.MeasurementUnit;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.repository.AlertRuleRepository;
import com.enginertugrul.iotsensormonitor.service.sensor.SensorService;
import com.enginertugrul.iotsensormonitor.support.temperature.TemperatureUnitConverter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;




@Service
public class AlertRuleServiceImpl implements AlertRuleService {


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
    public void createNumericThresholdRule(Long ownerId, NumericThresholdAlertRuleForm form, TemperatureUnit preferredTemperatureUnit) {

        Sensor sensor = sensorService.getSensorForUser(form.getSensorId(), ownerId);

        Double canonicalThreshold = toCanonicalThreshold(sensor,form.getThresholdValue(),preferredTemperatureUnit);

        AlertRule rule = AlertRule.numericThreshold(sensor,
                form.getComparisonOperator(),
                canonicalThreshold,
                form.getCooldownMinutes());

        alertRuleRepository.save( rule);


    }





    @Override
    @Transactional
    public void createMotionDetectedRule(Long ownerId, MotionEventAlertRuleForm form) {

        Sensor sensor = sensorService.getSensorForUser(form.getSensorId(), ownerId);

        AlertRule rule = AlertRule.motionDetected(sensor,form.getCooldownMinutes());

        alertRuleRepository.save(rule);

    }




    @Override
    @Transactional
    public void setAlertRuleEnabled(Long ownerId, Long alertRuleId, Boolean enabled) {
        AlertRule alertRule = getOwnedAlertRule(alertRuleId, ownerId);
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




    private AlertRule getOwnedAlertRule(Long alertRuleId, Long ownerId ) {

        return alertRuleRepository.findByIdAndOwnerId(alertRuleId, ownerId)
                .orElseThrow(() -> new NoSuchElementException("Alert rule not found"));
    }





    private Double toCanonicalThreshold(Sensor sensor, Double submittedThreshold, TemperatureUnit preferredTemperatureUnit) {

        return switch (sensor.getType()) {
            case TEMPERATURE -> temperatureUnitConverter.convertToCelsius(
                                    submittedThreshold,
                                    preferredTemperatureUnit);

            case HUMIDITY -> submittedThreshold;

            default -> throw new IllegalArgumentException("Numeric sensor required");
        };

    }




    private AlertRuleListItemDTO toListItem( AlertRule rule, TemperatureUnit preferredTemperatureUnit) {

        Sensor sensor = rule.getSensor();

        return new AlertRuleListItemDTO(
                rule.getId(),
                sensor.getId(),
                sensor.getName(),
                sensor.getInstallationLocation(),
                sensor.getType(),
                sensor.isActive(),
                rule.getRuleType(),
                rule.getComparisonOperator(),
                rule.getEventType(),
                toDisplayThreshold(rule, preferredTemperatureUnit),
                rule.getCooldownMinutes(),
                rule.isEnabled()
        );
    }




    private Double toDisplayThreshold(AlertRule rule, TemperatureUnit preferredTemperatureUnit) {

        if (rule.getRuleType() == AlertRuleType.EVENT_DETECTED) {
            return null;
        }

        SensorType sensorType = rule.getSensor().getType();

        if (!SensorMeasurementPolicy.supportsNumericMeasurements(sensorType)) {
            throw new IllegalStateException("Numeric rule belongs to a non-numeric sensor");
        }

        MeasurementUnit expectedUnit = SensorMeasurementPolicy.requireCanonicalUnit(sensorType);

        if (rule.getThresholdUnit() != expectedUnit) {
            throw new IllegalStateException("Stored threshold unit does not match sensor type");
        }

        if (sensorType == SensorType.TEMPERATURE) {

            return temperatureUnitConverter.convertFromCelsius(
                            rule.getThresholdValue(),
                            preferredTemperatureUnit);
        }

        return rule.getThresholdValue();
    }













}
