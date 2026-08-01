package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.service.user.recovery.PasswordRecoveryCodeDelivery;

public interface PasswordRecoveryNotificationDispatcher {

    void send(PasswordRecoveryCodeDelivery delivery);
}