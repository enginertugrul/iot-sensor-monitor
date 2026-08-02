package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.service.user.verification.EmailVerificationCodeDelivery;

public interface EmailVerificationNotificationDispatcher {

    void send(EmailVerificationCodeDelivery delivery);
}