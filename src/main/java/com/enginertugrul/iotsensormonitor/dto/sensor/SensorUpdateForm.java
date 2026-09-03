package com.enginertugrul.iotsensormonitor.dto.sensor;

import com.enginertugrul.iotsensormonitor.validation.ValidZoneId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class SensorUpdateForm {

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
    private String installationLocation;

    @NotBlank
    @Size(max = 64)
    @ValidZoneId
    private String timezone;
}