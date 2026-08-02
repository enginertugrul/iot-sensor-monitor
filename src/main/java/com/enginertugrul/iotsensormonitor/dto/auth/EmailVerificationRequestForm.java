package com.enginertugrul.iotsensormonitor.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
public class EmailVerificationRequestForm {

    @NotBlank(message = "{emailVerification.emailRequired}")
    @Email(message = "{emailVerification.emailInvalid}")
    @Size(max = 320, message = "{emailVerification.emailLength}")
    private String email;
}