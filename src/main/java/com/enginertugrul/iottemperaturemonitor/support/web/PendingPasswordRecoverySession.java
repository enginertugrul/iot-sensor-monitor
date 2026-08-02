package com.enginertugrul.iottemperaturemonitor.support.web;

import com.enginertugrul.iottemperaturemonitor.entity.user.AppUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.Objects;
import java.util.Optional;




public final class PendingPasswordRecoverySession {

    private static final String EMAIL_ATTRIBUTE =
            PendingPasswordRecoverySession.class.getName() + ".email";

    private PendingPasswordRecoverySession() {}

    public static void start(HttpServletRequest request,String email) {
        Objects.requireNonNull(request,"request must not be null");

        HttpSession session = getOrRotateSession(request);
        session.setAttribute(EMAIL_ATTRIBUTE,AppUser.normalizeEmail(email));
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