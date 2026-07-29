package com.enginertugrul.iottemperaturemonitor.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PasswordsMatchValidator implements ConstraintValidator<PasswordsMatch, PasswordConfirmation> {

    @Override
    public boolean isValid(PasswordConfirmation value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        String password = value.getPassword();
        String confirmPassword = value.getConfirmPassword();

        if (password == null || confirmPassword == null || password.isBlank() || confirmPassword.isBlank()) {
            return true;
        }

        if (password.equals(confirmPassword)) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate()).addPropertyNode("confirmPassword").addConstraintViolation();

        return false;
    }
}