package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.repository.AppUserRepository;
import com.enginertugrul.iotsensormonitor.security.AuthenticatedUser;
import com.enginertugrul.iotsensormonitor.support.web.PublicLocaleSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;




public class UserPreferenceLocaleResolver implements LocaleResolver {




    private final AppUserRepository appUserRepository;

    public UserPreferenceLocaleResolver(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }




    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Locale publicLocale = PublicLocaleSession.findPreferredLanguage(request)
                .map(preferredLanguage -> preferredLanguage.toLocale())
                .orElse(Locale.ENGLISH);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return appUserRepository.findById(user.getAppUserId())
                    .map(appUser -> appUser.getPreferredLanguage().toLocale())
                    .orElse(publicLocale);
        }

        return publicLocale;
    }



    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        // Locale is controlled by the user's stored preference.
    }


}