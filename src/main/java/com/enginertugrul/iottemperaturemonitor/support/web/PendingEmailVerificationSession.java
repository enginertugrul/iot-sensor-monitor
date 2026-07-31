package com.enginertugrul.iottemperaturemonitor.support.web;

import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import com.enginertugrul.iottemperaturemonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Objects;
import java.util.Optional;

public final class PendingEmailVerificationSession {

    private static final String EMAIL_ATTRIBUTE =
            PendingEmailVerificationSession.class.getName() + ".email";

    private static final String LANGUAGE_ATTRIBUTE =
            PendingEmailVerificationSession.class.getName() + ".language";

    private PendingEmailVerificationSession() {}



    public static void start(HttpServletRequest request, String email, PreferredLanguage preferredLanguage) {

        Objects.requireNonNull(request, "request must not be null");
        PreferredLanguage requiredLanguage = Objects.requireNonNull(preferredLanguage, "preferredLanguage must not be null");

        HttpSession session = getOrRotateSession(request);
        session.setAttribute(EMAIL_ATTRIBUTE,AppUser.normalizeEmail(email));
        session.setAttribute(LANGUAGE_ATTRIBUTE,requiredLanguage);
    }




    public static Optional<String> findEmail(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return Optional.empty();
        }

        Object value = session.getAttribute(EMAIL_ATTRIBUTE);
        return value instanceof String email ? Optional.of(email) : Optional.empty();
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

    public static void clearEmail(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return;
        }

        session.removeAttribute(EMAIL_ATTRIBUTE);
        request.changeSessionId();
    }

    private static HttpSession getOrRotateSession(HttpServletRequest request) {
        HttpSession existingSession = request.getSession(false);

        if (existingSession == null) {
            return request.getSession(true);
        }

        request.changeSessionId();
        return existingSession;
    }
}