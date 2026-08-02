package com.enginertugrul.iottemperaturemonitor.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class PasswordRecoveryRequestForm {

    @NotBlank(message = "{passwordRecovery.emailRequired}")
    @Email(message = "{passwordRecovery.emailInvalid}")
    @Size(max = 320, message = "{passwordRecovery.emailLength}")
    private String email;
}