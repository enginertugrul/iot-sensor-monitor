package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.dto.user.PasswordChangeForm;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangeResult;
import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangeService;
import com.enginertugrul.iotsensormonitor.support.web.PublicLocaleSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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






@Controller
@RequestMapping("/user/preferences/password")
public class PasswordChangeController {

    private static final String VIEW_NAME = "change-password";
    private static final String REDIRECT_TO_PASSWORD_CHANGE = "redirect:/user/preferences/password";

    private final PasswordChangeService passwordChangeService;
    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();



    public PasswordChangeController(PasswordChangeService passwordChangeService) {
        this.passwordChangeService = passwordChangeService;
    }






    @GetMapping
    public String getPasswordChangePage(Model model) {
        if (!model.containsAttribute("form")) {
            model.addAttribute("form",new PasswordChangeForm());
        }

        return VIEW_NAME;
    }





    @PostMapping
    public String changePassword(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @ModelAttribute("form") PasswordChangeForm form,
            BindingResult bindingResult,
            Locale locale,
            HttpServletRequest request,
            HttpServletResponse response,
            RedirectAttributes redirectAttributes) {

        if (bindingResult.hasErrors()) {
            return redirectWithErrors(form,bindingResult,redirectAttributes);
        }

        PasswordChangeResult result = passwordChangeService.changePassword(user.getAppUserId(),form.getCurrentPassword(),form.getPassword());

        if (result == PasswordChangeResult.PASSWORD_CHANGED) {
            form.clearSensitiveValues();
            logoutHandler.logout(request,response,SecurityContextHolder.getContext().getAuthentication());
            PublicLocaleSession.remember(request,locale);
            redirectAttributes.addFlashAttribute("passwordChanged",true);
            return "redirect:/login";
        }

        if (result == PasswordChangeResult.CURRENT_PASSWORD_INVALID) {
            bindingResult.rejectValue("currentPassword","passwordChange.currentPasswordInvalid");
        } else if (result == PasswordChangeResult.NEW_PASSWORD_INVALID) {
            bindingResult.rejectValue("password","passwordChange.newPasswordInvalid");
        } else if (result == PasswordChangeResult.NEW_PASSWORD_UNCHANGED) {
            bindingResult.rejectValue("password","passwordChange.newPasswordUnchanged");
        } else {
            throw new IllegalStateException("Unexpected password change result: " + result);
        }

        return redirectWithErrors(form,bindingResult,redirectAttributes);
    }








    private String redirectWithErrors(PasswordChangeForm submittedForm,BindingResult sourceBindingResult,RedirectAttributes redirectAttributes) {
        PasswordChangeForm sanitizedForm = new PasswordChangeForm();
        BindingResult sanitizedBindingResult = copyWithoutRejectedValues(sanitizedForm,"form",sourceBindingResult);

        submittedForm.clearSensitiveValues();
        redirectAttributes.addFlashAttribute("form",sanitizedForm);
        redirectAttributes.addFlashAttribute(BindingResult.MODEL_KEY_PREFIX + "form",sanitizedBindingResult);

        return REDIRECT_TO_PASSWORD_CHANGE;
    }






    private BindingResult copyWithoutRejectedValues(Object target,String objectName,BindingResult sourceBindingResult) {
        BeanPropertyBindingResult sanitizedBindingResult = new BeanPropertyBindingResult(target,objectName);

        for (ObjectError error : sourceBindingResult.getAllErrors()) {
            if (error instanceof FieldError fieldError) {
                sanitizedBindingResult.addError(withoutRejectedValue(objectName,fieldError));
                continue;
            }

            sanitizedBindingResult.addError(new ObjectError(objectName,error.getCodes(),error.getArguments(),error.getDefaultMessage()));
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

}