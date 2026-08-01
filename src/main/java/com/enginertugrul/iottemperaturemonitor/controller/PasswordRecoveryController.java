package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.auth.PasswordRecoveryRequestForm;
import com.enginertugrul.iottemperaturemonitor.dto.auth.PasswordResetForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.service.user.recovery.PasswordRecoveryResult;
import com.enginertugrul.iottemperaturemonitor.service.user.recovery.PasswordRecoveryService;
import com.enginertugrul.iottemperaturemonitor.support.web.PendingPasswordRecoverySession;
import com.enginertugrul.iottemperaturemonitor.support.web.PublicLocaleSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Optional;




@Controller
@RequestMapping("/forgot-password")
public class PasswordRecoveryController {

    private static final String VIEW_NAME = "forgot-password";
    private static final String REDIRECT_TO_RECOVERY = "redirect:/forgot-password";

    private final PasswordRecoveryService passwordRecoveryService;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public PasswordRecoveryController(PasswordRecoveryService passwordRecoveryService) {
        this.passwordRecoveryService = passwordRecoveryService;
    }

    @GetMapping
    public String getRecoveryPage(Model model,HttpServletRequest request) {
        if (!model.containsAttribute("requestForm")) {
            model.addAttribute("requestForm",new PasswordRecoveryRequestForm());
        }

        if (!model.containsAttribute("resetForm")) {
            model.addAttribute("resetForm",new PasswordResetForm());
        }

        Optional<String> pendingEmail = PendingPasswordRecoverySession.findEmail(request);
        model.addAttribute("pendingRecovery",pendingEmail.isPresent());
        pendingEmail.ifPresent(email -> model.addAttribute("maskedEmail",maskEmail(email)));

        return VIEW_NAME;
    }




    @PostMapping("/request")
    public String requestCode(
            @Valid @ModelAttribute("requestForm") PasswordRecoveryRequestForm requestForm,
            BindingResult bindingResult,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {

        if (bindingResult.hasErrors()) {
            PendingPasswordRecoverySession.clearEmail(request);
            return redirectWithErrors("requestForm",requestForm,bindingResult,redirectAttributes);
        }

        String normalizedEmail = AppUser.normalizeEmail(requestForm.getEmail());
        PendingPasswordRecoverySession.start(request,normalizedEmail);

        passwordRecoveryService.requestResetCode(normalizedEmail,request.getRemoteAddr());
        redirectAttributes.addFlashAttribute("codeRequested",true);
        return REDIRECT_TO_RECOVERY;

    }




    @PostMapping("/reset")
    public String resetPassword(
            @Valid @ModelAttribute("resetForm") PasswordResetForm resetForm,
            BindingResult bindingResult,
            Locale locale,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes
    ) {

        Optional<String> pendingEmail = PendingPasswordRecoverySession.findEmail(request);

        if (pendingEmail.isEmpty()) {
            resetForm.clearSensitiveValues();
            return REDIRECT_TO_RECOVERY;
        }

        if (bindingResult.hasErrors()) {
            return redirectWithResetErrors(resetForm,bindingResult,redirectAttributes);
        }

        PasswordRecoveryResult result = passwordRecoveryService.resetPassword(
                pendingEmail.get(),
                resetForm.getCode(),
                resetForm.getPassword(),
                request.getRemoteAddr()
        );

        if (result == PasswordRecoveryResult.PASSWORD_RESET) {
            resetForm.clearSensitiveValues();
            logoutHandler.logout(request,response, SecurityContextHolder.getContext().getAuthentication());
            PublicLocaleSession.remember(request,locale);
            redirectAttributes.addFlashAttribute("passwordReset",true);
            return "redirect:/login";
        }

        bindingResult.rejectValue("code","passwordRecovery.codeInvalid");
        return redirectWithResetErrors(resetForm,bindingResult,redirectAttributes);
    }





    @PostMapping("/resend")
    public String resendCode(HttpServletRequest request,RedirectAttributes redirectAttributes) {
        Optional<String> pendingEmail = PendingPasswordRecoverySession.findEmail(request);

        if (pendingEmail.isEmpty()) {
            return REDIRECT_TO_RECOVERY;
        }

        passwordRecoveryService.requestResetCode(pendingEmail.get(),request.getRemoteAddr());
        redirectAttributes.addFlashAttribute("codeRequested",true);
        return REDIRECT_TO_RECOVERY;
    }




    @PostMapping("/change-address")
    public String changeAddress(HttpServletRequest request) {
        PendingPasswordRecoverySession.clearEmail(request);
        return REDIRECT_TO_RECOVERY;
    }







    private String redirectWithErrors(
            String formAttribute,
            Object form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes
    ) {

        redirectAttributes.addFlashAttribute(formAttribute,form);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + formAttribute,bindingResult);
        return REDIRECT_TO_RECOVERY;
    }




    private String redirectWithResetErrors(
            PasswordResetForm submittedForm,
            BindingResult sourceBindingResult,
            RedirectAttributes redirectAttributes
    ) {
        PasswordResetForm sanitizedForm = new PasswordResetForm();
        BindingResult sanitizedBindingResult = copyWithoutRejectedValues(
                sanitizedForm,
                "resetForm",
                sourceBindingResult
        );

        submittedForm.clearSensitiveValues();
        redirectAttributes.addFlashAttribute("resetForm",sanitizedForm);
        redirectAttributes.addFlashAttribute(
                BindingResult.MODEL_KEY_PREFIX + "resetForm",
                sanitizedBindingResult
        );

        return REDIRECT_TO_RECOVERY;
    }




    private BindingResult copyWithoutRejectedValues(Object target,String objectName,BindingResult sourceBindingResult) {
        BeanPropertyBindingResult sanitizedBindingResult = new BeanPropertyBindingResult(target,objectName);

        for (ObjectError error : sourceBindingResult.getAllErrors()) {
            if (error instanceof FieldError fieldError) {
                sanitizedBindingResult.addError(withoutRejectedValue(objectName,fieldError));
                continue;
            }

            sanitizedBindingResult.addError(
                    new ObjectError(objectName,error.getCodes(),error.getArguments(),error.getDefaultMessage())
            );
        }

        return sanitizedBindingResult;
    }




    private FieldError withoutRejectedValue(String objectName,FieldError fieldError) {
        return new FieldError(
                objectName,
                fieldError.getField(),
                null,
                fieldError.isBindingFailure(),
                fieldError.getCodes(),
                fieldError.getArguments(),
                fieldError.getDefaultMessage()
        );
    }




    private String maskEmail(String email) {
        int separatorIndex = email.lastIndexOf('@');

        if (separatorIndex < 1) {
            return "***";
        }

        String localPart = email.substring(0,separatorIndex);
        String domainPart = email.substring(separatorIndex);

        if (localPart.length() == 1) {
            return localPart.charAt(0) + "***" + domainPart;
        }

        return localPart.charAt(0) + "***"
                + localPart.charAt(localPart.length() - 1)
                + domainPart;
    }



}