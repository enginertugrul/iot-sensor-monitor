package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.config.EmailVerificationMailConfig;
import com.enginertugrul.iottemperaturemonitor.service.user.verification.EmailVerificationCodeDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class EmailVerificationNotificationListener {

    private final Logger logger = LoggerFactory.getLogger(EmailVerificationNotificationListener.class);
    private final EmailVerificationNotificationDispatcher notificationDispatcher;
    private final TaskExecutor mailExecutor;

    public EmailVerificationNotificationListener(
            EmailVerificationNotificationDispatcher notificationDispatcher,
            @Qualifier(EmailVerificationMailConfig.EMAIL_VERIFICATION_MAIL_EXECUTOR) TaskExecutor mailExecutor
    ) {
        this.notificationDispatcher = notificationDispatcher;
        this.mailExecutor = mailExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationCodeIssued(EmailVerificationCodeDelivery delivery) {
        try {
            mailExecutor.execute(() -> sendSafely(delivery));
        } catch (TaskRejectedException exception) {
            logFailure(delivery,exception);
        }
    }

    private void sendSafely(EmailVerificationCodeDelivery delivery) {
        try {
            notificationDispatcher.send(delivery);
        } catch (RuntimeException exception) {
            logFailure(delivery,exception);
        }
    }

    private void logFailure(EmailVerificationCodeDelivery delivery, RuntimeException exception) {
        logger.error(
                "Email verification delivery failed. userId={}, failureType={}",
                delivery.userId(),
                exception.getClass().getSimpleName()
        );
    }
}