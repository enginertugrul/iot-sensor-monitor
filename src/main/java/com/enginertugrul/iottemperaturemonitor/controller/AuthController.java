package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.auth.RegisterUserForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.entity.user.TemperatureUnit;
import com.enginertugrul.iottemperaturemonitor.service.user.AppUserService;
import com.enginertugrul.iottemperaturemonitor.support.web.PendingEmailVerificationSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.DateTimeException;
import java.time.ZoneId;




@Controller
public class AuthController {


    private final AppUserService appUserService;


    public AuthController(AppUserService appUserService) {
        this.appUserService = appUserService;
    }





    @GetMapping("/login")
    public String getLoginPage() {

        return "login";
    }





    @GetMapping("/register")
    public String getRegisterPage(Model model) {

        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new RegisterUserForm());
        }

        addRegistrationOptions(model);
        return "register";
    }





    @PostMapping("/register")
    public String register(
            @Valid @ModelAttribute("form") RegisterUserForm form,
            BindingResult bindingResult,
            Model model,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {


        if (bindingResult.hasErrors()) {
            addRegistrationOptions(model);
            return "register";
        }

        AppUser registeredUser;

        try {
            registeredUser = appUserService.createUser(form);

        } catch (DateTimeException ex) {
            bindingResult.rejectValue("preferredTimezone", "timezone.invalid");
            addRegistrationOptions(model);
            return "register";
        } catch (IllegalArgumentException ex) {
            bindingResult.rejectValue("email", "auth.alreadyRegistered");
            addRegistrationOptions(model);
            return "register";
        }

        PendingEmailVerificationSession.start( request, registeredUser.getEmail(), registeredUser.getPreferredLanguage() );

        redirectAttributes.addFlashAttribute("accountCreated", true);
        return "redirect:/verify-email";
    }







    private void addRegistrationOptions(Model model) {
        model.addAttribute("languages", PreferredLanguage.values());
        model.addAttribute("temperatureUnits", TemperatureUnit.values());
        model.addAttribute("timezones", ZoneId.getAvailableZoneIds().stream().sorted().toList());
    }




}
