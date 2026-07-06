package com.enginertugrul.iottemperaturemonitor.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = IsFiniteDoubleValidator.class)
@Target({
        ElementType.FIELD,
        ElementType.METHOD,
        ElementType.PARAMETER,
        ElementType.ANNOTATION_TYPE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsFiniteDouble {

    String message() default "{validation.finiteDouble}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}