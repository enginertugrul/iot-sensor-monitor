package com.enginertugrul.iottemperaturemonitor.dto.auth;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerificationCodeForm {

    @NotNull(message = "{emailVerification.codeRequired}")
    @Pattern(regexp = "[0-9]{8}", message = "{emailVerification.codeFormat}")
    private String code;
}