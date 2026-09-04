package com.enginertugrul.iotsensormonitor.controller.advice;

import com.enginertugrul.iotsensormonitor.controller.AlertRuleController;
import com.enginertugrul.iotsensormonitor.controller.SensorController;
import com.enginertugrul.iotsensormonitor.exception.AlertRuleNotFoundException;
import com.enginertugrul.iotsensormonitor.exception.SensorNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.ModelAndView;

@ControllerAdvice(assignableTypes = {SensorController.class,AlertRuleController.class})
public class PageExceptionHandler {

    @ExceptionHandler({SensorNotFoundException.class,AlertRuleNotFoundException.class})
    public ModelAndView handleNotFound() {
        return new ModelAndView("error/404",HttpStatus.NOT_FOUND);
    }
}