package com.enginertugrul.iottemperaturemonitor.dto.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertEventType;
import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertRuleType;
import com.enginertugrul.iottemperaturemonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;




public record AlertRuleListItemDTO(
        Long id,
        Long sensorId,
        String sensorName,
        String sensorHomeLocation,
        SensorType sensorType,
        AlertRuleType ruleType,
        ComparisonOperator comparisonOperator,
        AlertEventType eventType,
        Double thresholdValue,
        Integer cooldownMinutes,
        boolean enabled
) {
}