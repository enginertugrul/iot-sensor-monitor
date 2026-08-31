package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.sensor.CreatedSensorDTO;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorForm;
import com.enginertugrul.iotsensormonitor.dto.sensor.SensorUpdateForm;
import com.enginertugrul.iotsensormonitor.entity.sensor.Sensor;
import com.enginertugrul.iotsensormonitor.entity.sensor.SensorType;
import com.enginertugrul.iotsensormonitor.exception.DuplicateSensorNameException;
import com.enginertugrul.iotsensormonitor.exception.SensorTimezoneLockedException;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.sensor.SensorService;
import com.enginertugrul.iotsensormonitor.support.timezone.TimezoneCatalog;
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
            RedirectAttributes redirectAttributes) {

        Long ownerId = authenticatedUser.getAppUserId();

        if (bindingResult.hasErrors()) {
            addPageData(model, ownerId);
            return "sensors";
        }

        CreatedSensorDTO createdSensor;

        try {
            createdSensor =  sensorService.createSensor(ownerId, form);
        } catch (DuplicateSensorNameException ex) {
            bindingResult.rejectValue("name", "sensors.nameDuplicate");
            addPageData(model, ownerId);
            return "sensors";
        }

        redirectAttributes.addFlashAttribute("successMessage", true);
        redirectAttributes.addFlashAttribute("createdSensorName", createdSensor.sensorName());
        redirectAttributes.addFlashAttribute("createdSensorToken", createdSensor.rawIngestionToken());

        return "redirect:/user/sensors";
    }





    @GetMapping("/{sensorId}/edit")
    public String getSensorsEditPage(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            Model model) {

        Long ownerId =  authenticatedUser.getAppUserId();

        if (!model.containsAttribute("form")) {
            model.addAttribute("form",sensorService.getSensorUpdateForm(sensorId,ownerId) );
        }

        addEditPageData(model, sensorId, ownerId);
        return "sensor-edit";
    }





    @PostMapping("/{sensorId}/edit")
    public String updateSensor(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            @Valid @ModelAttribute("form") SensorUpdateForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        Long ownerId = authenticatedUser.getAppUserId();

        if(bindingResult.hasErrors()) {
            addEditPageData(model, sensorId, ownerId);
            return "sensor-edit";
        }

        try {
            sensorService.updateSensor(sensorId, ownerId, form);
        } catch (DuplicateSensorNameException ex) {
            bindingResult.rejectValue("name", "sensors.nameDuplicate");
            addEditPageData(model, sensorId, ownerId);
            return "sensor-edit";
        } catch (SensorTimezoneLockedException ex) {
            bindingResult.rejectValue("timezone", "sensors.timezoneLocked");
            addEditPageData(model, sensorId, ownerId);
            return "sensor-edit";
        }

        redirectAttributes.addFlashAttribute("sensorUpdated", true);

        return "redirect:/user/sensors";

    }






    @GetMapping("/{sensorId}/delete")
    public String getSensorDeletePage(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            Model model) {

        Long ownerId = authenticatedUser.getAppUserId();
        Sensor sensor = sensorService.getSensorForUser(sensorId,ownerId);

        model.addAttribute("sensorId",sensor.getId());
        model.addAttribute("sensorName",sensor.getName());
        model.addAttribute("sensorType",sensor.getType());
        model.addAttribute("sensorCity",sensor.getCity());
        model.addAttribute("sensorDistrict",sensor.getDistrict());
        model.addAttribute("sensorHomeLocation",sensor.getHomeLocation());

        return "sensor-delete";
    }





    @PostMapping("/{sensorId}/delete")
    public String deleteSensor(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @PathVariable Long sensorId,
            RedirectAttributes redirectAttributes) {

        sensorService.deleteSensor(sensorId,authenticatedUser.getAppUserId());

        redirectAttributes.addFlashAttribute("sensorDeleted",true);

        return "redirect:/user/sensors";
    }



    private void addPageData(Model model, Long ownerId) {
        model.addAttribute("sensorTypes", SensorType.values());
        model.addAttribute("timezoneOptions", timezoneCatalog.getTimezoneOptions());
        model.addAttribute("sensors", sensorService.getSensorsForUser(ownerId));
    }


    private void addEditPageData(Model model,Long sensorId,Long ownerId) {

        Sensor sensor = sensorService.getSensorForUser(sensorId,ownerId);

        model.addAttribute("sensorId", sensor.getId());
        model.addAttribute("sensorName", sensor.getName());
        model.addAttribute("sensorType", sensor.getType());
        model.addAttribute("sensorTimezoneLocked",sensor.hasRecordedReadings());
        model.addAttribute("sensorTimezone",sensor.getTimezone());
        model.addAttribute("sensorTimezoneDisplayName",timezoneCatalog.toDisplayName(sensor.getTimezone()));
        model.addAttribute("timezoneOptions",timezoneCatalog.getTimezoneOptions() );
    }


}