package com.enginertugrul.iotsensormonitor.validation;


import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = PasswordsMatchValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordsMatch {

    String message() default "{auth.passwordMismatch}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
