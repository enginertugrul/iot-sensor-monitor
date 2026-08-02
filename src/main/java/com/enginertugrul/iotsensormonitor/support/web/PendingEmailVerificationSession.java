package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.entity.user.AppUser;
import com.enginertugrul.iotsensormonitor.entity.user.PreferredLanguage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Objects;
import java.util.Optional;




public final class PendingEmailVerificationSession {


    private static final String EMAIL_ATTRIBUTE = PendingEmailVerificationSession.class.getName() + ".email";


    private PendingEmailVerificationSession() {}



    public static void start(HttpServletRequest request, String email, PreferredLanguage preferredLanguage) {

        Objects.requireNonNull(request, "request must not be null");
        PreferredLanguage requiredLanguage = Objects.requireNonNull(preferredLanguage, "preferredLanguage must not be null");

        HttpSession session = getOrRotateSession(request);
        session.setAttribute(EMAIL_ATTRIBUTE,AppUser.normalizeEmail(email));
        PublicLocaleSession.remember(request,requiredLanguage);
    }




    public static Optional<String> findEmail(HttpServletRequest request) {
        HttpSession session = request.getSession(false);

        if (session == null) {
            return Optional.empty();
        }

        Object value = session.getAttribute(EMAIL_ATTRIBUTE);
        return value instanceof String email ? Optional.of(email) : Optional.empty();
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