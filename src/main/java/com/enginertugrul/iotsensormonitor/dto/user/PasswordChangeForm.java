package com.enginertugrul.iotsensormonitor.dto.user;

import com.enginertugrul.iotsensormonitor.validation.PasswordConfirmation;
import com.enginertugrul.iotsensormonitor.validation.PasswordsMatch;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;



@Getter
@Setter
@PasswordsMatch(message = "{passwordChange.passwordMismatch}")
public class PasswordChangeForm implements PasswordConfirmation {

    @NotBlank(message = "{passwordChange.currentPasswordRequired}")
    @Size(min = 8,max = 72,message = "{passwordChange.currentPasswordLength}")
    private String currentPassword;

    @NotBlank(message = "{passwordChange.newPasswordRequired}")
    @Size(min = 8,max = 72,message = "{passwordChange.newPasswordLength}")
    private String password;

    @NotBlank(message = "{passwordChange.confirmPasswordRequired}")
    private String confirmPassword;

    public void clearSensitiveValues() {
        currentPassword = null;
        password = null;
        confirmPassword = null;
    }

}