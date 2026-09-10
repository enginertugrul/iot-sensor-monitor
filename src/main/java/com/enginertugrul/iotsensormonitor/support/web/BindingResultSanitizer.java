package com.enginertugrul.iotsensormonitor.support.web;

import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;




public final class BindingResultSanitizer {

    private BindingResultSanitizer() {}


    public static BindingResult copyWithoutRejectedValues(Object sanitizedTarget,String objectName,BindingResult sourceBindingResult) {
        BeanPropertyBindingResult sanitizedBindingResult = new BeanPropertyBindingResult(sanitizedTarget,objectName);

        for (ObjectError error : sourceBindingResult.getAllErrors()) {
            if (error instanceof FieldError fieldError) {
                sanitizedBindingResult.addError(withoutRejectedValue(objectName,fieldError));
                continue;
            }

            sanitizedBindingResult.addError(new ObjectError(objectName,error.getCodes(),error.getArguments(),error.getDefaultMessage()));
        }

        return sanitizedBindingResult;
    }

    private static FieldError withoutRejectedValue(String objectName,FieldError fieldError) {
        return new FieldError(objectName,
                fieldError.getField(),
                null,
                fieldError.isBindingFailure(),
                fieldError.getCodes(),
                fieldError.getArguments(),
                fieldError.getDefaultMessage());
    }
}