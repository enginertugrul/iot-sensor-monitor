package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iottemperaturemonitor.dto.alert.MotionEventAlertRuleForm;
import com.enginertugrul.iottemperaturemonitor.dto.alert.NumericThresholdAlertRuleForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.util.List;

public interface AlertRuleService {


    List<AlertRuleListItemDTO> getAlertRulesForUser(Long ownerId, TemperatureUnit preferredTemperatureUnit);

    void createNumericThresholdRule(Long ownerId, NumericThresholdAlertRuleForm form , TemperatureUnit preferredTemperatureUnit);

    void createMotionDetectedRule(Long ownerId, MotionEventAlertRuleForm form);

    void setAlertRuleEnabled(Long ownerId ,Long alertRuleId, Boolean enabled);

    void deleteAlertRule(Long ownerId, Long alertRuleId);


}
