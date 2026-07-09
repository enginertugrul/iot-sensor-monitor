package com.enginertugrul.iottemperaturemonitor.service.notification;

import com.enginertugrul.iottemperaturemonitor.service.alert.AlertTriggeredEvent;

public interface AlertNotificationDispatcher {

    void send(AlertTriggeredEvent event);

}
