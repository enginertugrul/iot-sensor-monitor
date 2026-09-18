package com.enginertugrul.iotsensormonitor.service.notification.alert;

import com.enginertugrul.iotsensormonitor.config.EmailAlertMailConfig;
import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;
import com.enginertugrul.iotsensormonitor.service.notification.retry.EmailDeliveryRetryService;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;



@Component
public class EmailAlertNotificationListener {

    private final Logger logger = LoggerFactory.getLogger(EmailAlertNotificationListener.class);
    private final AlertNotificationDispatcher alertNotificationDispatcher;
    private final EmailDeliveryRetryService emailDeliveryRetryService;
    private final TaskExecutor mailExecutor;

    public EmailAlertNotificationListener(
            AlertNotificationDispatcher alertNotificationDispatcher,
            EmailDeliveryRetryService emailDeliveryRetryService,
            @Qualifier(EmailAlertMailConfig.EMAIL_ALERT_MAIL_EXECUTOR) TaskExecutor mailExecutor
    ) {
        this.alertNotificationDispatcher = alertNotificationDispatcher;
        this.emailDeliveryRetryService = emailDeliveryRetryService;
        this.mailExecutor = mailExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertTriggered(AlertTriggeredEvent event) {
        try {
            mailExecutor.execute(() -> sendSafely(event));
        } catch (TaskRejectedException exception) {
            logger.error("Alert email delivery rejected. alertRuleId={}, sensorId={}, failureType={}",
                    event.context().alertRuleId(),event.context().sensor().id(),exception.getClass().getSimpleName());
        }
    }

    private void sendSafely(AlertTriggeredEvent event) {
        try {
            emailDeliveryRetryService.send("ALERT",event.context().alertRuleId(),() -> alertNotificationDispatcher.send(event));
        } catch (RuntimeException exception) {
            logger.error("Alert email delivery failed. alertRuleId={}, sensorId={}, failureType={}",
                    event.context().alertRuleId(),event.context().sensor().id(),exception.getClass().getSimpleName());
        }
    }
}