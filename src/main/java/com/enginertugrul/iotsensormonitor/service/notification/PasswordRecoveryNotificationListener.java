package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.config.PasswordRecoveryMailConfig;
import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryCodeDelivery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;





@Component
public class PasswordRecoveryNotificationListener {



    private final Logger logger = LoggerFactory.getLogger(PasswordRecoveryNotificationListener.class);
    private final PasswordRecoveryNotificationDispatcher notificationDispatcher;
    private final TaskExecutor mailExecutor;


    public PasswordRecoveryNotificationListener(
            PasswordRecoveryNotificationDispatcher notificationDispatcher,
            @Qualifier(PasswordRecoveryMailConfig.PASSWORD_RECOVERY_MAIL_EXECUTOR) TaskExecutor mailExecutor
    ) {
        this.notificationDispatcher = notificationDispatcher;
        this.mailExecutor = mailExecutor;
    }




    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordRecoveryCodeIssued(PasswordRecoveryCodeDelivery delivery) {
        try {
            mailExecutor.execute(() -> sendSafely(delivery));
        } catch (TaskRejectedException exception) {
            logFailure(delivery,exception);
        }
    }




    private void sendSafely(PasswordRecoveryCodeDelivery delivery) {
        try {
            notificationDispatcher.send(delivery);
        } catch (RuntimeException exception) {
            logFailure(delivery,exception);
        }
    }




    private void logFailure(PasswordRecoveryCodeDelivery delivery, RuntimeException exception) {
        logger.error(
                "Password recovery delivery failed. userId={}, failureType={}",
                delivery.userId(),
                exception.getClass().getSimpleName()
        );
    }



}