package com.enginertugrul.iottemperaturemonitor.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = ZoneIdValidator.class)
public @interface ValidZoneId {
    String message() default "{timezone.invalid}";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}