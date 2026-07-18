package com.enginertugrul.iottemperaturemonitor.dto.alert;

import com.enginertugrul.iottemperaturemonitor.entity.alert.AlertCooldownPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MotionEventAlertRuleForm {

    @NotNull(message = "{alertRules.sensorRequired}")
    private Long sensorId;

    @NotNull(message = "{alertRules.cooldownRequired}")
    @Min(
            value = AlertCooldownPolicy.MIN_MINUTES,
            message = "{alertRules.cooldownMin}"
    )
    @Max(
            value = AlertCooldownPolicy.MAX_MINUTES,
            message = "{alertRules.cooldownMax}"
    )
    private Integer cooldownMinutes;
}