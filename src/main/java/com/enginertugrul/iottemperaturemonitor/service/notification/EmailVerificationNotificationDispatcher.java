package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.service.user.verification.EmailVerificationCodeDelivery;

public interface EmailVerificationNotificationDispatcher {

    void send(EmailVerificationCodeDelivery delivery);
}