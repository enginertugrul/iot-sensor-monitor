package com.enginertugrul.iotsensormonitor.service.notification.recovery;

import com.enginertugrul.iotsensormonitor.service.user.recovery.PasswordRecoveryCodeDelivery;

public interface PasswordRecoveryNotificationDispatcher {

    void send(PasswordRecoveryCodeDelivery delivery);
}