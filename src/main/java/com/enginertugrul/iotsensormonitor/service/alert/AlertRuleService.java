package com.enginertugrul.iotsensormonitor.service.alert;

import com.enginertugrul.iotsensormonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iotsensormonitor.dto.alert.MotionEventAlertRuleForm;
import com.enginertugrul.iotsensormonitor.dto.alert.NumericThresholdAlertRuleForm;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;

import java.util.List;

public interface AlertRuleService {


    List<AlertRuleListItemDTO> getAlertRulesForUser(Long ownerId, TemperatureUnit preferredTemperatureUnit);

    void createNumericThresholdRule(Long ownerId, NumericThresholdAlertRuleForm form , TemperatureUnit preferredTemperatureUnit);

    void createMotionDetectedRule(Long ownerId, MotionEventAlertRuleForm form);

    void setAlertRuleEnabled(Long ownerId ,Long alertRuleId, Boolean enabled);

    void deleteAlertRule(Long ownerId, Long alertRuleId);


}
