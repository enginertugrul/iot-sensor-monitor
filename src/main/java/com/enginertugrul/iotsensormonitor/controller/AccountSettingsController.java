package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.user.AccountSettingsPageDTO;
import com.enginertugrul.iotsensormonitor.dto.user.UserPreferencesForm;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iotsensormonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.user.AppUserService;
import com.enginertugrul.iotsensormonitor.support.timezone.TimezoneCatalog;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;




@Controller
@RequestMapping("/user/settings")
public class AccountSettingsController {


    private final AppUserService appUserService;
    private final TimezoneCatalog timezoneCatalog;



    public AccountSettingsController(AppUserService appUserService, TimezoneCatalog timezoneCatalog) {
        this.appUserService = appUserService;
        this.timezoneCatalog = timezoneCatalog;
    }





    @GetMapping
    public String getAccountSettingsPage(@AuthenticationPrincipal AuthenticatedUser user, Model model) {

        addPageData(user.getAppUserId(), model);
        return "account-settings";
    }





    @PostMapping
    public String updatePreferences(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @ModelAttribute("form") UserPreferencesForm form,
            BindingResult bindingResult,
            Model model,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            addPageData(user.getAppUserId(), model);
            return "account-settings";
        }

        appUserService.updatePreferences(user.getAppUserId(), form);
        redirectAttributes.addFlashAttribute("preferencesUpdated", true);
        return "redirect:/user/settings";
    }






    private void addPageData(Long userId,Model model) {
        AccountSettingsPageDTO page = appUserService.getAccountSettingsPage(userId);

        if (!model.containsAttribute("form")) {
            model.addAttribute("form",page.form());
        }

        model.addAttribute("accountDetails",page);
        model.addAttribute("languages",PreferredLanguage.values());
        model.addAttribute("temperatureUnits",TemperatureUnit.values());
        model.addAttribute("timezoneOptions",timezoneCatalog.getTimezoneOptions());
    }



}
