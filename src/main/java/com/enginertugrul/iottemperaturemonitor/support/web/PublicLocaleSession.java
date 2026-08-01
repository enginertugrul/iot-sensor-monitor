package com.enginertugrul.iottemperaturemonitor.support.web;

import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;



public final class PublicLocaleSession {

    private static final String LANGUAGE_ATTRIBUTE =
            PublicLocaleSession.class.getName() + ".language";

    private PublicLocaleSession() {}



    public static void remember(HttpServletRequest request,PreferredLanguage preferredLanguage) {
        Objects.requireNonNull(request,"request must not be null");
        PreferredLanguage requiredLanguage = Objects.requireNonNull(preferredLanguage,"preferredLanguage must not be null");
        request.getSession(true).setAttribute(LANGUAGE_ATTRIBUTE,requiredLanguage);
    }



    public static void remember(HttpServletRequest request,Locale locale) {
        Objects.requireNonNull(locale,"locale must not be null");

        for (PreferredLanguage preferredLanguage : PreferredLanguage.values()) {
            if (preferredLanguage.toLocale().getLanguage().equalsIgnoreCase(locale.getLanguage())) {
                remember(request,preferredLanguage);
                return;
            }
        }

        remember(request,PreferredLanguage.ENGLISH);
    }




    public static Optional<PreferredLanguage> findPreferredLanguage(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return Optional.empty();
        }

        Object value = session.getAttribute(LANGUAGE_ATTRIBUTE);
        return value instanceof PreferredLanguage preferredLanguage
                ? Optional.of(preferredLanguage)
                : Optional.empty();
    }


}