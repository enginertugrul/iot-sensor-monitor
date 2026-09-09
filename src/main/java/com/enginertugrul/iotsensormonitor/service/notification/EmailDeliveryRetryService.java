package com.enginertugrul.iotsensormonitor.service.notification;

import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryListener;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryState;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.core.retry.Retryable;
import org.springframework.stereotype.Service;




@NullMarked
@Service
public class EmailDeliveryRetryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmailDeliveryRetryService.class);

    private final EmailRetryPolicy policy;

    public EmailDeliveryRetryService(EmailRetryPolicy policy) {
        this.policy = policy;
    }

    public void send(String notificationType,long referenceId,Runnable sendOperation) {
        RetryTemplate retryTemplate = new RetryTemplate(policy.getRetryPolicy());

        retryTemplate.setRetryListener(new RetryListener() {
            @Override
            public void beforeRetry(RetryPolicy retryPolicy,Retryable<?> retryable,RetryState retryState) {
                LOGGER.warn("Retrying email delivery. notificationType={}, referenceId={}, attempt={}/{}, failureType={}",
                        notificationType,referenceId,retryState.getRetryCount() + 1,policy.getMaximumAttempts(),retryState.getLastException().getClass().getSimpleName());
            }
        });

        retryTemplate.invoke(sendOperation);
    }
}