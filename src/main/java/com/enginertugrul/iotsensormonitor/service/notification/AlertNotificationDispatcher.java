package com.enginertugrul.iotsensormonitor.service.notification;

import com.enginertugrul.iotsensormonitor.service.alert.AlertTriggeredEvent;

public interface AlertNotificationDispatcher {

    void send(AlertTriggeredEvent event);

}
