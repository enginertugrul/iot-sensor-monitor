package com.enginertugrul.iottemperaturemonitor.security;

import com.enginertugrul.iottemperaturemonitor.support.web.PendingEmailVerificationSession;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class EmailVerificationAuthenticationSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private static final String VERIFICATION_REQUIRED_URL = "/verify-email?verificationRequired=true";

    private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

    public EmailVerificationAuthenticationSuccessHandler() {
        setDefaultTargetUrl("/");
        setAlwaysUseDefaultTargetUrl(true);
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof AuthenticatedUser authenticatedUser)) {
            logoutHandler.logout(request,response,authentication);
            throw new ServletException("Unsupported authenticated principal");
        }

        if (authenticatedUser.isEmailVerified()) {
            super.onAuthenticationSuccess(request,response,authentication);
            return;
        }

        logoutHandler.logout(request,response,authentication);
        PendingEmailVerificationSession.start(request,authenticatedUser.getUsername(),authenticatedUser.getPreferredLanguage());
        getRedirectStrategy().sendRedirect(request,response,VERIFICATION_REQUIRED_URL);
    }
}