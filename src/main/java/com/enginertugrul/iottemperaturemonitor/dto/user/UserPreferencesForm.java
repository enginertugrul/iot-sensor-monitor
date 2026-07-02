package com.enginertugrul.iottemperaturemonitor.dto.user;


import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.validation.ValidZoneId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserPreferencesForm {

    @NotNull
    private PreferredLanguage preferredLanguage;

    @NotNull
    private TemperatureUnit temperatureUnit;

    @NotBlank
    @Size(max= 64)
    @ValidZoneId
    private String preferredTimezone;

}
