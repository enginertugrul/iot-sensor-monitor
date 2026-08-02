package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;
import org.slf4j.*;
import org.springframework.mail.MailException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EmailAlertNotificationListener {

    private final Logger logger = LoggerFactory.getLogger(EmailAlertNotificationListener.class);
    private final EmailAlertNotificationSender emailAlertNotificationSender;

    public EmailAlertNotificationListener(EmailAlertNotificationSender emailAlertNotificationSender) {
        this.emailAlertNotificationSender = emailAlertNotificationSender;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertTriggered(AlertTriggeredEvent event) {
        try {
            emailAlertNotificationSender.send(event);
        } catch (MailException ex) {
            logger.error("Failed to send alert email. alertRuleId={}", event.context().alertRuleId(), ex);
        }
    }
}