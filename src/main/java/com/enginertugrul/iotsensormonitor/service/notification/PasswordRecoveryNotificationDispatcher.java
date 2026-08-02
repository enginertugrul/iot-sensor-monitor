package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryCodeDelivery;

public interface PasswordRecoveryNotificationDispatcher {

    void send(PasswordRecoveryCodeDelivery delivery);
}