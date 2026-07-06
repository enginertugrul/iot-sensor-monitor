package com.enginertugrul.iottemperaturemonitor.controller;


import com.enginertugrul.iottemperaturemonitor.dto.alert.AlertRuleForm;
import com.enginertugrul.iottemperaturemonitor.dto.sensor.SensorListItemDTO;
import com.enginertugrul.iottemperaturemonitor.entity.alert.ComparisonOperator;
import com.enginertugrul.iottemperaturemonitor.entity.sensor.SensorType;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.security.AuthenticatedUser;
import com.enginertugrul.iottemperaturemonitor.service.alert.AlertRuleService;
import com.enginertugrul.iottemperaturemonitor.service.sensor.SensorService;
import com.enginertugrul.iottemperaturemonitor.service.user.AppUserService;
import com.enginertugrul.iottemperaturemonitor.support.temperature.TemperatureUnitConverter;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/user/alert-rules")
public class AlertRuleController {

    private final AlertRuleService alertRuleService;
    private final SensorService sensorService;
    private final AppUserService appUserService;
    private final TemperatureUnitConverter temperatureUnitConverter;


    public AlertRuleController(AlertRuleService alertRuleService, SensorService sensorService, AppUserService appUserService, TemperatureUnitConverter temperatureUnitConverter) {
        this.alertRuleService = alertRuleService;
        this.sensorService = sensorService;
        this.appUserService = appUserService;
        this.temperatureUnitConverter = temperatureUnitConverter;
    }



    @GetMapping
    public String getAlertRulesPage(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        Long ownerId = user.getAppUserId();

        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new AlertRuleForm());
        }

        addPageData(model,ownerId);
        return "alert-rules";
    }



    @PostMapping
    public String createAlertRuleForm(@AuthenticationPrincipal AuthenticatedUser user,
                                      @Valid @ModelAttribute("form") AlertRuleForm form,
                                      BindingResult bindingResult,
                                      Model model,
                                      RedirectAttributes redirectAttributes) {

        Long ownerId = user.getAppUserId();

        if (bindingResult.hasErrors()) {
            addPageData(model,ownerId);
            return "alert-rules";
        }

        TemperatureUnit preferredTemperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

        try {
            alertRuleService.createTemperatureThresholdRule(ownerId, form, preferredTemperatureUnit);
        } catch (IllegalArgumentException ex) {
            bindingResult.reject("alertRule.invalid", ex.getMessage());
            addPageData(model,ownerId);
            return "alert-rules";
        }

        redirectAttributes.addFlashAttribute("alertRuleCreated", true);
        return "redirect:/user/alert-rules";

    }


    @PostMapping("/{alertRuleId}/enable")
    public String enableAlertRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long alertRuleId,
            RedirectAttributes redirectAttributes
    ) {

        alertRuleService.setAlertRuleEnabled(user.getAppUserId(),alertRuleId, true);
        redirectAttributes.addFlashAttribute("alertRuleEnabled", true);
        return "redirect:/user/alert-rules";

    }


    @PostMapping("/{alertRuleId}/disable")
    public String disableAlertRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long alertRuleId,
            RedirectAttributes redirectAttributes
    ) {
        alertRuleService.setAlertRuleEnabled(user.getAppUserId(),alertRuleId, false);
        redirectAttributes.addFlashAttribute("alertRuleDisabled", true);
        return "redirect:/user/alert-rules";
    }


    @PostMapping("{alertRuleId}/delete")
    public String deleteAlertRule(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable Long alertRuleId,
            RedirectAttributes redirectAttributes
    ) {

        alertRuleService.deleteAlertRule(user.getAppUserId(),alertRuleId);
        redirectAttributes.addFlashAttribute("alertRuleDeleted", true);
        return "redirect:/user/alert-rules";

    }













    private void addPageData(Model model, Long ownerId) {
        TemperatureUnit preferredTemperatureUnit = appUserService.getPreferredTemperatureUnit(ownerId);

        List<SensorListItemDTO> temperatureSensors = sensorService.getSensorsForUser(ownerId)
                .stream()
                .filter(sensor -> sensor.type() == SensorType.TEMPERATURE)
                .toList();

        model.addAttribute("sensors", temperatureSensors);
        model.addAttribute("comparisonOperators", ComparisonOperator.values());
        model.addAttribute("alertRules", alertRuleService.getAlertRulesForUser(ownerId, preferredTemperatureUnit));
        model.addAttribute("temperatureUnitSymbol", temperatureUnitConverter.getSymbol(preferredTemperatureUnit));
    }


}
