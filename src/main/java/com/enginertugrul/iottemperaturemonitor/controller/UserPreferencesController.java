package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.security.AuthenticatedUser;
import com.enginertugrul.iottemperaturemonitor.service.user.AppUserService;
import com.enginertugrul.iottemperaturemonitor.timezone.TimezoneCatalog;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class UserPreferencesController {


    private final AppUserService appUserService;
    private final TimezoneCatalog timezoneCatalog;

    public UserPreferencesController(AppUserService appUserService, TimezoneCatalog timezoneCatalog) {
        this.appUserService = appUserService;
        this.timezoneCatalog = timezoneCatalog;
    }


    @GetMapping
    public String getPreferencesPage(@AuthenticationPrincipal AuthenticatedUser user, Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", appUserService.getPreferences(user.getAppUserId()));
        }

        addPageData(model);
        return "preferences";
    }

    @PostMapping
    public String updatePreferences(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @ModelAttribute("form") UserPreferencesForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes
    ) {
        if (bindingResult.hasErrors()) {
            addPageData(model);
            return "preferences";
        }

        appUserService.updatePreferences(user.getAppUserId(), form);
        redirectAttributes.addFlashAttribute("successMessage", "Preferences updated.");
        return "redirect:/user/preferences";
    }

    private void addPageData(Model model) {
        model.addAttribute("languages", PreferredLanguage.values());
        model.addAttribute("temperatureUnits", TemperatureUnit.values());
        model.addAttribute("timezoneOptions", timezoneCatalog.getTimezoneOptions());
    }


}
