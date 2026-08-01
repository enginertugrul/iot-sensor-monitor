package com.enginertugrul.iottemperaturemonitor.security;

import com.enginertugrul.iottemperaturemonitor.service.user.recovery.PasswordResetCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class PasswordResetSessionExpirationListener {

    private final Logger logger = LoggerFactory.getLogger(PasswordResetSessionExpirationListener.class);
    private final SessionRegistry sessionRegistry;

    public PasswordResetSessionExpirationListener(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetCompleted(PasswordResetCompletedEvent event) {
        try {
            sessionRegistry.getAllPrincipals().stream()
                    .filter(AuthenticatedUser.class::isInstance)
                    .map(AuthenticatedUser.class::cast)
                    .filter(principal -> principal.getAppUserId().equals(event.userId()))
                    .forEach(this::expireSessions);
        } catch (RuntimeException exception) {
            logger.error("Password reset session expiration failed. userId={}, failureType={}",event.userId(),exception.getClass().getSimpleName());
        }
    }

    private void expireSessions(AuthenticatedUser principal) {
        sessionRegistry.getAllSessions(principal,false).forEach(SessionInformation::expireNow);
    }
}