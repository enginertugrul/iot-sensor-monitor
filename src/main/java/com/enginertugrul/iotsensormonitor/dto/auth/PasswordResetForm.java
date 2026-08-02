package com.enginertugrul.iotsensormonitor.dto.auth;

import com.enginertugrul.iotsensormonitor.validation.PasswordConfirmation;
import com.enginertugrul.iotsensormonitor.validation.PasswordsMatch;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;




@Getter
@Setter
@PasswordsMatch(message = "{passwordRecovery.passwordMismatch}")
public class PasswordResetForm implements PasswordConfirmation {

    @NotBlank(message = "{passwordRecovery.codeRequired}")
    @Pattern(regexp = "[0-9]{8}", message = "{passwordRecovery.codeFormat}")
    private String code;

    @NotBlank(message = "{passwordRecovery.passwordRequired}")
    @Size(min = 8, max = 72, message = "{passwordRecovery.passwordLength}")
    private String password;

    @NotBlank(message = "{passwordRecovery.confirmPasswordRequired}")
    private String confirmPassword;

    public void clearSensitiveValues() {
        code = null;
        password = null;
        confirmPassword = null;
    }
}