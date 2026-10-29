package com.enginertugrul.iotsensormonitor.dto.auth;


import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.validation.MaxUtf8Bytes;
import com.enginertugrul.iotsensormonitor.validation.PasswordConfirmation;
import com.enginertugrul.iotsensormonitor.validation.PasswordsMatch;
import com.enginertugrul.iotsensormonitor.validation.ValidZoneId;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
@PasswordsMatch
public class RegisterUserForm implements PasswordConfirmation {

    @NotBlank(message = "{auth.emailRequired}")
    @Email(message = "{auth.emailInvalid}")
    @Size(max = 320, message = "{auth.emailLength}")
    private String email;


    @NotBlank(message = "{auth.passwordRequired}")
    @Size(min = 8, message = "{auth.passwordTooShort}")
    @MaxUtf8Bytes(value = 72, message = "{auth.passwordTooLong}")
    private String password;


    @NotBlank(message = "{auth.confirmPasswordRequired}")
    private String confirmPassword;


    @NotNull(message = "{preferences.languageRequired}")
    private PreferredLanguage preferredLanguage = PreferredLanguage.ENGLISH;

    @NotNull(message = "{preferences.temperatureUnitRequired}")
    private TemperatureUnit preferredTemperatureUnit = TemperatureUnit.CELSIUS;

    @NotBlank(message = "{preferences.timezoneRequired}")
    @Size(max = 64, message = "{preferences.timezoneLength}")
    @ValidZoneId
    private String preferredTimezone = "UTC";

}
