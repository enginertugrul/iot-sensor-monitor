package com.enginertugrul.iottemperaturemonitor.dto.sensor;

import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.validation.ValidZoneId;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class SensorForm {

    @NotNull
    private SensorType type = SensorType.TEMPERATURE;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 100)
    private String city;

    @NotBlank
    @Size(max = 100)
    private String district;

    @NotBlank
    @Size(max = 100)
    private String homeLocation;

    @NotBlank
    @Size(max = 64)
    @ValidZoneId
    private String timezone = "UTC";
}