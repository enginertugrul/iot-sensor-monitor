package com.enginertugrul.iottemperaturemonitor.dto.alert;


import com.enginertugrul.iottemperaturemonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iottemperaturemonitor.validation.IsFiniteDouble;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AlertRuleForm {

    @NotNull(message = "{alertRules.sensorRequired}")
    private Long sensorId;

    @NotNull(message= "{alertRules.comparisonOperatorRequired}")
    private ComparisonOperator comparisonOperator;

    @NotNull(message = "{alertRules.thresholdRequired}")
    @IsFiniteDouble
    private Double thresholdValue;

}
