package com.enginertugrul.iotsensormonitor.dto.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertEventType;
import com.enginertugrul.iotsensormonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;




public record AlertRuleListItemDTO(
        Long id,
        Long sensorId,
        String sensorName,
        String sensorInstallationLocation,
        SensorType sensorType,
        AlertRuleType ruleType,
        ComparisonOperator comparisonOperator,
        AlertEventType eventType,
        Double thresholdValue,
        Integer cooldownMinutes,
        boolean enabled
) {
}