package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorForm;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.security.AuthenticatedUser;
import com.enginertugrul.iottemperaturemonitor.service.sensor.SensorService;
import com.enginertugrul.iottemperaturemonitor.timezone.TimezoneCatalog;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/user/sensors")
public class SensorController {

    private final SensorService sensorService;
    private final TimezoneCatalog timezoneCatalog;

    public SensorController(SensorService sensorService, TimezoneCatalog timezoneCatalog) {
        this.sensorService = sensorService;
        this.timezoneCatalog = timezoneCatalog;
    }

    @GetMapping
    public String getSensorsPage(@AuthenticationPrincipal AuthenticatedUser authenticatedUser, Model model) {
        Long ownerId = authenticatedUser.getAppUserId();

        if (!model.containsAttribute("form")) {
            SensorForm form = new SensorForm();
            form.setTimezone(sensorService.getDefaultTimezoneForUser(ownerId));
            model.addAttribute("form", form);
        }

        addPageData(model, ownerId);
        return "sensors";
    }

    @PostMapping
    public String createSensor(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @ModelAttribute("form") SensorForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        Long ownerId = authenticatedUser.getAppUserId();

        if (bindingResult.hasErrors()) {
            addPageData(model, ownerId);
            return "sensors";
        }

        CreatedSensorDTO createdSensor;

        try {
            createdSensor =  sensorService.createSensor(ownerId, form);
        } catch (IllegalArgumentException ex) {
            bindingResult.rejectValue("name", "sensors.nameDuplicate", ex.getMessage());
            addPageData(model, ownerId);
            return "sensors";
        }

        redirectAttributes.addFlashAttribute("successMessage", "Sensor created successfully.");
        redirectAttributes.addFlashAttribute("createdSensorName", createdSensor.sensorName());
        redirectAttributes.addFlashAttribute("createdSensorToken", createdSensor.rawIngestionToken());

        return "redirect:/user/sensors";
    }

    private void addPageData(Model model, Long ownerId) {
        model.addAttribute("sensorTypes", SensorType.values());
        model.addAttribute("timezoneOptions", timezoneCatalog.getTimezoneOptions());
        model.addAttribute("sensors", sensorService.getSensorsForUser(ownerId));
    }
}