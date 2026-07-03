package com.enginertugrul.iottemperaturemonitor.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.DateTimeException;
import java.time.ZoneId;

public class ZoneIdValidator implements ConstraintValidator<ValidZoneId, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }

        try {
            ZoneId.of(value);
            return true;
        } catch (DateTimeException ex) {
            return false;
        }
    }
}