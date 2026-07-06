package com.enginertugrul.iottemperaturemonitor.service.alert;

import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleForm;
import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleListItemDTO;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;

import java.util.List;

public interface AlertRuleService {


    List<AlertRuleListItemDTO> getAlertRulesForUser(Long ownerId, TemperatureUnit preferredTemperatureUnit);

    void createTemperatureThresholdRule(Long ownerId, AlertRuleForm form , TemperatureUnit preferredTemperatureUnit);

    void setAlertRuleEnabled(Long ownerId ,Long id, Boolean enabled);

    void deleteAlertRule(Long ownerId, Long alertRuleId);


}
