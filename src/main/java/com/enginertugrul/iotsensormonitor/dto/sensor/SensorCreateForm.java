package com.enginertugrul.iotsensormonitor.dto.sensor;

import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.validation.ValidZoneId;
import jakarta.validation.constraints.*;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class SensorCreateForm {

    @NotNull(message = "{sensors.typeRequired}")
    private SensorType type = SensorType.TEMPERATURE;

    @NotBlank(message = "{sensors.nameRequired}")
    @Size(max = 100, message = "{sensors.nameLength}")
    private String name;

    @NotBlank(message = "{sensors.cityRequired}")
    @Size(max = 100, message = "{sensors.cityLength}")
    private String city;

    @NotBlank(message = "{sensors.districtRequired}")
    @Size(max = 100, message = "{sensors.districtLength}")
    private String district;

    @NotBlank(message = "{sensors.installationLocationRequired}")
    @Size(max = 100, message = "{sensors.installationLocationLength}")
    private String installationLocation;

    @NotBlank(message = "{sensors.timezoneRequired}")
    @Size(max = 64, message = "{sensors.timezoneLength}")
    @ValidZoneId
    private String timezone = "UTC";
}