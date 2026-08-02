package com.enginertugrul.iotsensormonitor.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class IsFiniteDoubleValidator implements ConstraintValidator<IsFiniteDouble, Double> {

    @Override
    public boolean isValid(Double value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }

        return !value.isNaN() && !value.isInfinite();
    }
}