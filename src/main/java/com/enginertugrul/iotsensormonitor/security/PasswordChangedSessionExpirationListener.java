package com.enginertugrul.iotsensormonitor.security;

import com.enginertugrul.iotsensormonitor.service.user.password.PasswordChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;




@Component
public class PasswordChangedSessionExpirationListener {

    private final Logger logger = LoggerFactory.getLogger(PasswordChangedSessionExpirationListener.class);
    private final SessionRegistry sessionRegistry;

    public PasswordChangedSessionExpirationListener(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordChanged(PasswordChangedEvent event) {
        try {
            sessionRegistry.getAllPrincipals().stream()
                    .filter(AuthenticatedUser.class::isInstance)
                    .map(AuthenticatedUser.class::cast)
                    .filter(principal -> principal.getAppUserId().equals(event.userId()))
                    .forEach(this::expireSessions);
        } catch (RuntimeException exception) {
            logger.error("Password change session expiration failed. userId={}, failureType={}",event.userId(),exception.getClass().getSimpleName());
        }
    }

    private void expireSessions(AuthenticatedUser principal) {
        sessionRegistry.getAllSessions(principal,false).forEach(SessionInformation::expireNow);
    }

}