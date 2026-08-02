package com.enginertugrul.iotsensormonitor.dto.sensor;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.validation.ValidZoneId;
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