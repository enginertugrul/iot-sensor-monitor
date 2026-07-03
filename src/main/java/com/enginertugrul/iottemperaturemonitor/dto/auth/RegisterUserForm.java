package com.enginertugrul.iottemperaturemonitor.dto.auth;


import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.validation.ValidZoneId;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterUserForm {

    @NotBlank
    @Email
    @Size(max = 320)
    private String email;



    @NotBlank
    @Size(min = 8 , max = 72)
    private String password;



    @NotNull
    private PreferredLanguage preferredLanguage = PreferredLanguage.ENGLISH;

    @NotNull
    private TemperatureUnit preferredTemperatureUnit = TemperatureUnit.CELSIUS;

    @NotBlank
    @Size(max = 64)
    @ValidZoneId
    private String preferredTimezone = "UTC";

}
