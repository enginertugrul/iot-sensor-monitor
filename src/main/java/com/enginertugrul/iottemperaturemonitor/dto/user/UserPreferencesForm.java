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

    @NotNull(message = "{preferences.languageRequired}")
    private PreferredLanguage preferredLanguage;

    @NotNull(message = "{preferences.temperatureUnitRequired}")
    private TemperatureUnit temperatureUnit;

    @NotBlank(message = "{preferences.timezoneRequired}")
    @Size(max = 64, message = "{preferences.timezoneLength}")
    @ValidZoneId(message = "{preferences.timezoneInvalid}")
    private String preferredTimezone;

}