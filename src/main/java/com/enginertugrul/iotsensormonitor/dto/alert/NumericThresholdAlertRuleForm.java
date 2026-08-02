package com.enginertugrul.iotsensormonitor.dto.alert;

import com.enginertugrul.iotsensormonitor.entity.alert.AlertCooldownPolicy;
import com.enginertugrul.iotsensormonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iotsensormonitor.validation.IsFiniteDouble;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class NumericThresholdAlertRuleForm {

    @NotNull(message = "{alertRules.sensorRequired}")
    private Long sensorId;

    @NotNull(message ="{alertRules.comparisonOperatorRequired}")
    private ComparisonOperator comparisonOperator;

    @NotNull(message = "{alertRules.thresholdRequired}")
    @IsFiniteDouble
    private Double thresholdValue;

    @NotNull(message = "{alertRules.cooldownRequired}")
    @Min(value = AlertCooldownPolicy.MIN_MINUTES, message = "{alertRules.cooldownMin}")
    @Max(value = AlertCooldownPolicy.MAX_MINUTES, message = "{alertRules.cooldownMax}")
    private Integer cooldownMinutes;
}