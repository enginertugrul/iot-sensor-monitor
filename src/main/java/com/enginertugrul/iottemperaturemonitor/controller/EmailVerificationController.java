package com.enginertugrul.iottemperaturemonitor.controller;

import com.enginertugrul.iottemperaturemonitor.dto.auth.EmailVerificationCodeForm;
import com.enginertugrul.iottemperaturemonitor.dto.auth.EmailVerificationRequestForm;
import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import com.enginertugrul.iottemperaturemonitor.service.user.verification.EmailVerificationResult;
import com.enginertugrul.iottemperaturemonitor.service.user.verification.EmailVerificationService;
import com.enginertugrul.iottemperaturemonitor.support.web.PendingEmailVerificationSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Locale;
import java.util.Optional;





@Controller
@RequestMapping("/verify-email")
public class EmailVerificationController {

    private static final String VIEW_NAME = "email-verification";
    private static final String REDIRECT_TO_VERIFICATION = "redirect:/verify-email";

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }




    @GetMapping
    public String getVerificationPage(Model model, HttpServletRequest request) {

        if (!model.containsAttribute("requestForm")) {
            model.addAttribute("requestForm",new EmailVerificationRequestForm());
        }

        if (!model.containsAttribute("codeForm")) {
            model.addAttribute("codeForm",new EmailVerificationCodeForm());
        }

        Optional<String> pendingEmail = PendingEmailVerificationSession.findEmail(request);
        model.addAttribute("pendingVerification",pendingEmail.isPresent());
        pendingEmail.ifPresent(email -> model.addAttribute("maskedEmail",maskEmail(email)));

        return VIEW_NAME;
    }





    @PostMapping("/request")
    public String requestCode(
            @Valid @ModelAttribute("requestForm") EmailVerificationRequestForm requestForm,
            BindingResult bindingResult,
            Locale locale,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            PendingEmailVerificationSession.clearEmail(request);
            return redirectWithErrors("requestForm",requestForm,bindingResult,redirectAttributes);
        }

        String normalizedEmail = AppUser.normalizeEmail(requestForm.getEmail());

        PendingEmailVerificationSession.start(request, normalizedEmail, preferredLanguageFor(locale) );

        emailVerificationService.requestNewCode(normalizedEmail,request.getRemoteAddr());
        redirectAttributes.addFlashAttribute("codeRequested",true);
        return REDIRECT_TO_VERIFICATION;
    }





    @PostMapping
    public String verifyCode(
            @Valid @ModelAttribute("codeForm") EmailVerificationCodeForm codeForm,
            BindingResult bindingResult,
            HttpServletRequest request,
            RedirectAttributes redirectAttributes
    ) {

        Optional<String> pendingEmail = PendingEmailVerificationSession.findEmail(request);

        if (pendingEmail.isEmpty()) {
            return REDIRECT_TO_VERIFICATION;
        }

        if (bindingResult.hasErrors()) {
            codeForm.setCode(null);
            return redirectWithErrors("codeForm",codeForm,bindingResult,redirectAttributes);
        }

        EmailVerificationResult result = emailVerificationService.verifyCode(
                pendingEmail.get(),
                codeForm.getCode(),
                request.getRemoteAddr()
        );

        if (result == EmailVerificationResult.VERIFIED) {
            PendingEmailVerificationSession.clearEmail(request);
            redirectAttributes.addFlashAttribute("emailVerified",true);
            return "redirect:/login";
        }

        codeForm.setCode(null);
        bindingResult.rejectValue("code","emailVerification.codeInvalid");
        return redirectWithErrors("codeForm",codeForm,bindingResult,redirectAttributes);
    }





    @PostMapping("/resend")
    public String resendCode(
            HttpServletRequest request,
            RedirectAttributes redirectAttributes) {

        Optional<String> pendingEmail = PendingEmailVerificationSession.findEmail(request);

        if (pendingEmail.isEmpty()) {
            return REDIRECT_TO_VERIFICATION;
        }

        emailVerificationService.requestNewCode(pendingEmail.get(),request.getRemoteAddr());
        redirectAttributes.addFlashAttribute("codeRequested",true);
        return REDIRECT_TO_VERIFICATION;
    }




    @PostMapping("/change-address")
    public String changeAddress(HttpServletRequest request) {
        PendingEmailVerificationSession.clearEmail(request);
        return REDIRECT_TO_VERIFICATION;
    }





    private String redirectWithErrors(
            String formAttribute,
            Object form,
            BindingResult bindingResult,
            RedirectAttributes redirectAttributes) {

        redirectAttributes.addFlashAttribute(formAttribute,form);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + formAttribute, bindingResult);

        return REDIRECT_TO_VERIFICATION;
    }





    private PreferredLanguage preferredLanguageFor(Locale locale) {

        for (PreferredLanguage preferredLanguage : PreferredLanguage.values()) {
            if (preferredLanguage.toLocale().getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                return preferredLanguage;
            }
        }

        return PreferredLanguage.ENGLISH;
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